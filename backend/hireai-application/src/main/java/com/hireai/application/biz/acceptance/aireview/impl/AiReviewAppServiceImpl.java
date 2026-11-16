package com.hireai.application.biz.acceptance.aireview.impl;

import com.hireai.application.biz.acceptance.aireview.AiReviewAppService;
import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.ledger.settlement.impl.SettlementWriteAppServiceImpl;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.application.config.AiReviewSettings;
import com.hireai.application.port.aireview.AiReviewSubject;
import com.hireai.application.port.aireview.AiReviewerPort;
import com.hireai.application.port.aireview.AiReviewerUnavailableException;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.acceptance.aireview.enums.AiReviewStatus;
import com.hireai.domain.biz.acceptance.aireview.event.AiReviewRequestedDomainEvent;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewTerms;
import com.hireai.domain.biz.acceptance.aireview.repository.AiReviewRepository;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * See {@link AiReviewAppService}. The reviewer is called with no transaction open (a model call can
 * take seconds); the verdict is then recorded under the Task's row lock, so a Client clicking accept
 * on the web and a late verdict serialize. Money only moves here deterministically: the fee is
 * charged once a verdict exists and refunded otherwise, and a decisive verdict settles the Task from
 * its ruling category through the same settlement paths a dispute ruling uses (Invariant #3).
 */
@Slf4j
@Service
public class AiReviewAppServiceImpl implements AiReviewAppService {

    private final AiReviewRepository aiReviewRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;
    private final WalletWriteAppService walletWriteAppService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final DeliverySnapshotAppService deliverySnapshotAppService;
    private final AiReviewerPort reviewer;
    private final ValidationReportRepository reportRepository;
    private final AiReviewSettings settings;
    private final ReviewWindow reviewWindow;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AiReviewAppServiceImpl(AiReviewRepository aiReviewRepository, TaskRepository taskRepository,
                                  AgentRepository agentRepository, WalletWriteAppService walletWriteAppService,
                                  SettlementWriteAppService settlementWriteAppService,
                                  ReputationWriteAppService reputationWriteAppService,
                                  WebhookOutboxAppService webhookOutboxAppService,
                                  DeliverySnapshotAppService deliverySnapshotAppService, AiReviewerPort reviewer,
                                  ValidationReportRepository reportRepository,
                                  AiReviewSettings settings, ReviewWindow reviewWindow,
                                  ApplicationEventPublisher eventPublisher, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.aiReviewRepository = aiReviewRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
        this.walletWriteAppService = walletWriteAppService;
        this.settlementWriteAppService = settlementWriteAppService;
        this.reputationWriteAppService = reputationWriteAppService;
        this.webhookOutboxAppService = webhookOutboxAppService;
        this.deliverySnapshotAppService = deliverySnapshotAppService;
        this.reviewer = reviewer;
        this.reportRepository = reportRepository;
        this.settings = settings;
        this.reviewWindow = reviewWindow;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void freezeAtSubmit(UUID taskId, UUID clientId, AiReviewTerms terms) {
        tx.executeWithoutResult(status -> {
            walletWriteAppService.freeze(clientId, terms.fee(), taskId,
                    SettlementWriteAppServiceImpl.aiReviewCorrelation(taskId));
            aiReviewRepository.insert(AiReviewModel.frozen(taskId, terms, clock.instant()));
        });
        log.info("Task {}: AI Review fee {} frozen for {} criteria", taskId, terms.fee(), terms.criteria().size());
    }

    @Override
    public boolean requestIfFrozen(TaskModel task, boolean decisive) {
        Boolean requested = tx.execute(status -> {
            Optional<AiReviewModel> review = aiReviewRepository.lockByTaskId(task.id());
            if (review.isEmpty() || review.get().status() != AiReviewStatus.FROZEN) {
                return false;
            }
            aiReviewRepository.save(review.get().request(decisive, clock.instant()));
            eventPublisher.publishEvent(new AiReviewRequestedDomainEvent(task.id()));
            return true;
        });
        if (Boolean.TRUE.equals(requested)) {
            log.info("Task {}: AI Review requested ({})", task.id(), decisive ? "decisive, API/MCP" : "advisory, web");
        }
        return Boolean.TRUE.equals(requested);
    }

    @Override
    public void run(UUID taskId) {
        AiReviewModel review = aiReviewRepository.findByTaskId(taskId).orElse(null);
        if (review == null || review.status() != AiReviewStatus.REQUESTED) {
            return;
        }
        TaskModel task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.PENDING_REVIEW) {
            // The Client decided (web) before the reviewer ran: an advisory report is moot now.
            releaseRequested(taskId);
            return;
        }
        AiReviewerPort.Answer answer;
        try {
            answer = reviewer.review(subjectFor(task, review));
        } catch (RuntimeException e) {
            // Fail closed: no usable verdict is never a pass.
            boolean retryable = !(e instanceof AiReviewerUnavailableException u) || u.retryable();
            log.warn("Task {}: the AI reviewer gave no verdict (attempt {} of {}{}): {}", taskId,
                    review.attempts() + 1, settings.maxAttempts(), retryable ? "" : ", not retried", e.getMessage());
            recordFailure(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), retryable);
            return;
        }
        recordVerdict(taskId, answer);
    }

    private AiReviewSubject subjectFor(TaskModel task, AiReviewModel review) {
        if (task.result() != null && task.result().resultUrl() != null && !task.result().resultUrl().isBlank()) {
            DeliverySnapshot snapshot = deliverySnapshotAppService.findForResult(task.result()).orElse(null);
            if (snapshot != null) {
                byte[] content = deliverySnapshotAppService.content(snapshot).orElse(null);
                if (content != null) {
                    return new AiReviewSubject(task.title(), task.description(), review.criteria(), null, content,
                            snapshot.mediaType(), snapshot.sha256());
                }
            }
        }
        String text = task.result() == null ? null : task.result().resultPayloadJson();
        return new AiReviewSubject(task.title(), task.description(), review.criteria(), text == null ? "" : text,
                null, null, null);
    }

    private void recordVerdict(UUID taskId, AiReviewerPort.Answer answer) {
        tx.executeWithoutResult(status -> {
            TaskModel task = taskRepository.findByIdForUpdate(taskId).orElseThrow();
            AiReviewModel review = aiReviewRepository.lockByTaskId(taskId).orElseThrow();
            if (review.status() != AiReviewStatus.REQUESTED) {
                return; // an overlapping run recorded it first
            }
            AiReviewModel done;
            try {
                done = review.complete(answer.verdicts(), answer.model(), clock.instant());
            } catch (DomainException unusable) {
                // A criterion missing or judged twice: unusable, so unavailable and not retried.
                failLocked(task, review, unusable.getMessage(), false);
                return;
            }
            if (task.status() != TaskStatus.PENDING_REVIEW) {
                // The Client decided while the reviewer was thinking: no report to show, no fee taken.
                aiReviewRepository.save(review.release(clock.instant()));
                settlementWriteAppService.refundAiReviewFee(taskId, task.clientId(), review.fee());
                return;
            }
            aiReviewRepository.save(done);
            settlementWriteAppService.chargeAiReviewFee(taskId, task.clientId(), done.fee());
            log.info("Task {}: AI Review verdict {} ({}; fee {} charged)", taskId, done.rulingCategory(),
                    done.decisive() ? "decisive" : "advisory", done.fee());
            if (done.decisive()) {
                settleDecisively(task, done.rulingCategory());
            }
        });
    }

    /**
     * API/MCP (D38): the verdict is final and settles the Task through the same deterministic paths a
     * dispute ruling uses: FULFILLED pays 85/15, PARTIALLY_FULFILLED splits, NOT_FULFILLED refunds.
     */
    private void settleDecisively(TaskModel task, RulingCategory category) {
        UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId()).orElse(null);
        if (builderId == null && category != RulingCategory.NOT_FULFILLED) {
            throw new DomainException(ResultCode.NOT_FOUND, "No agent owner for version " + task.agentVersionId());
        }
        switch (category) {
            case FULFILLED -> settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId,
                    task.budget());
            case PARTIALLY_FULFILLED -> settlementWriteAppService.settleSplit(task.id(), task.clientId(), builderId,
                    task.budget());
            case NOT_FULFILLED -> settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        }
        TaskModel resolved = task.resolveByAiReview(category);
        taskRepository.save(resolved);
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                switch (category) {
                    case FULFILLED -> ReputationEventType.AI_REVIEW_PASSED;
                    case PARTIALLY_FULFILLED -> ReputationEventType.AI_REVIEW_PARTIAL;
                    case NOT_FULFILLED -> ReputationEventType.AI_REVIEW_FAILED;
                });
        if (category == RulingCategory.NOT_FULFILLED) {
            webhookOutboxAppService.enqueueFailed(resolved, "AI_REVIEW_NOT_FULFILLED");
        } else {
            webhookOutboxAppService.enqueueCompleted(resolved);
        }
        log.info("Task {} settled by its decisive AI Review: {} -> {}", task.id(), category, resolved.resolution());
    }

    private void recordFailure(UUID taskId, String reason, boolean retryable) {
        tx.executeWithoutResult(status -> {
            TaskModel task = taskRepository.findByIdForUpdate(taskId).orElseThrow();
            AiReviewModel review = aiReviewRepository.lockByTaskId(taskId).orElseThrow();
            if (review.status() == AiReviewStatus.REQUESTED) {
                failLocked(task, review, reason, retryable);
            }
        });
    }

    /**
     * One failed attempt, under the locks. Once it gives up the review is "unavailable": the fee is
     * refunded, and the Task settles as it would have without AI Review. On the web that changes
     * nothing (the Client reviews as usual); on API/MCP it is the deterministic checks' result.
     */
    private void failLocked(TaskModel task, AiReviewModel review, String reason, boolean retryable) {
        AiReviewModel failed = review.recordFailure(reason, retryable, settings.maxAttempts(), clock.instant());
        aiReviewRepository.save(failed);
        if (failed.status() != AiReviewStatus.FAILED) {
            return; // retried by the sweeper
        }
        settlementWriteAppService.refundAiReviewFee(task.id(), task.clientId(), failed.fee());
        log.warn("Task {}: AI Review unavailable after {} attempt(s); fee {} refunded", task.id(),
                failed.attempts(), failed.fee());
        if (failed.decisive() && task.status() == TaskStatus.PENDING_REVIEW) {
            fallBackToTheChecks(task);
        }
    }

    /**
     * What an API task gets without AI Review (as in ValidationAppServiceImpl): a PASS auto-settles as
     * accepted; a result the checks sent to review waits for the Client's review window.
     */
    private void fallBackToTheChecks(TaskModel task) {
        ValidationReportModel report = reportRepository.findLatestByTaskId(task.id()).orElse(null);
        if (report != null && report.isPass()) {
            UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                    .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                            "No agent owner for version " + task.agentVersionId()));
            TaskModel resolved = task.accept();
            settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());
            reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                    ReputationEventType.TASK_ACCEPTED);
            taskRepository.save(resolved);
            webhookOutboxAppService.enqueueCompleted(resolved);
            log.info("API task {}: no AI Review verdict, so its passed checks settle it (auto-accepted)", task.id());
            return;
        }
        taskRepository.stampReviewDeadline(task.id(), reviewWindow.closesAt(clock.instant()));
        if (report != null && report.needsReview()) {
            taskRepository.stampReviewReason(task.id(), report.anomalies().isEmpty() ? "UNDECIDED" : "ANOMALY");
        }
        log.info("API task {}: no AI Review verdict; the result waits for the Client's review", task.id());
    }

    private void releaseRequested(UUID taskId) {
        tx.executeWithoutResult(status -> {
            TaskModel task = taskRepository.findByIdForUpdate(taskId).orElseThrow();
            AiReviewModel review = aiReviewRepository.lockByTaskId(taskId).orElseThrow();
            if (review.status() == AiReviewStatus.REQUESTED && task.status() != TaskStatus.PENDING_REVIEW) {
                aiReviewRepository.save(review.release(clock.instant()));
                settlementWriteAppService.refundAiReviewFee(taskId, task.clientId(), review.fee());
                log.info("Task {}: decided before its AI Review ran; fee {} refunded", taskId, review.fee());
            }
        });
    }

    @Override
    public void releaseIfUnused(UUID taskId) {
        tx.executeWithoutResult(status -> {
            AiReviewModel review = aiReviewRepository.lockByTaskId(taskId).orElse(null);
            if (review == null || review.status() != AiReviewStatus.FROZEN) {
                return;
            }
            TaskModel task = taskRepository.findById(taskId).orElseThrow();
            aiReviewRepository.save(review.release(clock.instant()));
            settlementWriteAppService.refundAiReviewFee(taskId, task.clientId(), review.fee());
            log.info("Task {} ended ({}) before any result was reviewed; AI Review fee {} refunded", taskId,
                    task.status(), review.fee());
        });
    }

    @Override
    public boolean isDeciding(UUID taskId) {
        return aiReviewRepository.findByTaskId(taskId)
                .map(r -> r.decisive() && r.status() == AiReviewStatus.REQUESTED).orElse(false);
    }

    @Override
    public List<UUID> staleRequestedTaskIds() {
        Instant cutoff = clock.instant().minus(settings.retryAfter());
        return aiReviewRepository.findRequestedBefore(cutoff);
    }

    @Override
    public List<UUID> unusedTaskIds() {
        return aiReviewRepository.findFrozenOnEndedTasks();
    }

    @Override
    public Optional<AiReviewModel> findForClient(UUID taskId, UUID clientId) {
        return taskRepository.findById(taskId)
                .filter(task -> task.clientId().equals(clientId))
                .flatMap(task -> aiReviewRepository.findByTaskId(taskId));
    }
}
