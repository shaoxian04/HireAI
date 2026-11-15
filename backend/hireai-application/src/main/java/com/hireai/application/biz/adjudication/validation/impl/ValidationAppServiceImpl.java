package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ValidationAppServiceImpl implements ValidationAppService {

    private final ValidationDomainService validationDomainService;
    private final DeliverySnapshotAppService deliverySnapshotAppService;
    private final ValidationReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final AgentRepository agentRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final ReviewWindow reviewWindow;
    /** The app's Clock bean (ClockConfig): the review window opens at this instant. */
    private final Clock clock;

    @Override
    public TaskModel validateAndGate(TaskModel task) {
        // The first result is attempt 1; an Agent's revision after a rejection (#95) is the next one.
        int attempt = reportRepository.findLatestByTaskId(task.id())
                .map(previous -> previous.attemptNo() + 1).orElse(1);
        // The frozen Contract is the binding contract (Invariant #4). A Task submitted before
        // Contracts existed has none and finishes under its frozen output_spec, through the same
        // D39 mapping (ValidationDomainService#validate(OutputSpec, ...)).
        Contract contract = task.contract() != null ? task.contract() : task.outputSpec().toContract();
        // A file is fetched and frozen once, here, before any check (D24): every later step judges
        // the DeliverySnapshot, never the Agent's link.
        DeliveryOutcome delivery = validationDomainService.needsDelivery(contract, task.result())
                ? deliverySnapshotAppService.capture(contract, task.result())
                : null;
        ValidationReportModel report = validationDomainService.validate(contract, task.result(), delivery, attempt);
        reportRepository.save(report);

        if (report.isPass()) {
            TaskModel gated = task.passValidation(); // -> PENDING_REVIEW
            boolean apiSubmitted = apiKeyTaskRepository.findApiKeyIdByTask(task.id()).isPresent();
            if (apiSubmitted) {
                // Programmatic channel: deterministic immediate auto-settle. Reuse the accept settlement
                // (Invariant #3 — same money path, no LLM/human). Disputes stay human-channel only.
                UUID builderId = agentRepository.findOwnerByVersionId(task.agentVersionId())
                        .orElseThrow(() -> new DomainException(
                                ResultCode.NOT_FOUND,
                                "No agent owner for version " + task.agentVersionId()));
                TaskModel resolved = gated.accept(); // PENDING_REVIEW -> RESOLVED
                settlementWriteAppService.settleAccepted(task.id(), task.clientId(), builderId, task.budget());
                // Machine-verified conformance against the binding output_spec is real evidence and
                // is NOT discounted (#39). No Satisfaction event is possible — no human judged it —
                // which caps an API-only agent below one with human approval without any rule
                // saying so; the two-component model produces that ceiling on its own.
                reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(),
                        task.clientId(), ReputationEventType.TASK_ACCEPTED);
                taskRepository.save(resolved);
                webhookOutboxAppService.enqueueCompleted(resolved);
                log.info("API task {} passed validation -> auto-settled RESOLVED (payout to builder {})",
                        task.id(), builderId);
                return resolved;
            }
            taskRepository.save(gated);
            // Web channel: the Client reviews within the window, or silence counts as acceptance (#94).
            Instant reviewDeadline = reviewWindow.closesAt(clock.instant());
            taskRepository.stampReviewDeadline(task.id(), reviewDeadline);
            log.info("Task {} passed validation -> PENDING_REVIEW (review window closes {})",
                    task.id(), reviewDeadline);
            return gated;
        }
        TaskModel gated = task.failValidation();
        taskRepository.save(gated);
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        // The output missed the contract the builder declared (Invariant #4) — a zero-quality
        // sample against Reliability.
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(),
                task.clientId(), ReputationEventType.SPEC_VIOLATION);
        webhookOutboxAppService.enqueueFailed(gated, gated.status().name());
        log.info("Task {} failed validation -> SPEC_VIOLATION (refunded)", task.id());
        return gated;
    }
}
