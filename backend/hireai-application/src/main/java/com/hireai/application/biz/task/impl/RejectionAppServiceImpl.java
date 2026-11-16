package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.application.port.agent.AgentNoticePort;
import com.hireai.application.port.agent.RejectionNotice;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.model.RejectionWindows;
import com.hireai.domain.biz.task.rejection.repository.RejectionRepository;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DispatchTokenInvalidException;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The rejection response path (#95, spec D20/D22). Thin orchestration: the rules (which response is
 * allowed when, how long each window is, what silence means) live on {@link RejectionModel} and the
 * {@link TaskModel} transitions; this service locks, loads, applies them and moves money through the
 * settlement services, in the task's row-locked transaction.
 */
@Service
@Slf4j
@Transactional
public class RejectionAppServiceImpl implements RejectionAppService {

    /** How many times the sweeper tries to deliver one notice before leaving the Agent to its silence. */
    static final int MAX_NOTICE_ATTEMPTS = 5;

    private final TaskRepository taskRepository;
    private final RejectionRepository rejectionRepository;
    private final AgentRepository agentRepository;
    private final DispatchTokenService dispatchTokenService;
    private final ValidationAppService validationAppService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final DisputeAppService disputeAppService;
    private final AcceptedSettlement acceptedSettlement;
    private final AgentNoticePort agentNoticePort;
    private final OutputSpecJsonMapper outputSpecJsonMapper;
    private final RejectionWindows windows;
    private final Clock clock;
    private final String publicBaseUrl;

    public RejectionAppServiceImpl(TaskRepository taskRepository, RejectionRepository rejectionRepository,
                                   AgentRepository agentRepository, DispatchTokenService dispatchTokenService,
                                   ValidationAppService validationAppService,
                                   SettlementWriteAppService settlementWriteAppService,
                                   ReputationWriteAppService reputationWriteAppService,
                                   DisputeAppService disputeAppService, AcceptedSettlement acceptedSettlement,
                                   AgentNoticePort agentNoticePort, OutputSpecJsonMapper outputSpecJsonMapper,
                                   RejectionWindows windows, Clock clock,
                                   @Value("${hireai.platform.public-base-url:http://localhost:8080}")
                                   String publicBaseUrl) {
        this.taskRepository = taskRepository;
        this.rejectionRepository = rejectionRepository;
        this.agentRepository = agentRepository;
        this.dispatchTokenService = dispatchTokenService;
        this.validationAppService = validationAppService;
        this.settlementWriteAppService = settlementWriteAppService;
        this.reputationWriteAppService = reputationWriteAppService;
        this.disputeAppService = disputeAppService;
        this.acceptedSettlement = acceptedSettlement;
        this.agentNoticePort = agentNoticePort;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
        this.windows = windows;
        this.clock = clock;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void respond(UUID taskId, String bearerToken, AgentRejectionResponseInfo response) {
        DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken);
        if (!claims.taskId().equals(taskId)) {
            throw new DispatchTokenInvalidException(
                    "Dispatch token task " + claims.taskId() + " does not match response task " + taskId);
        }
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!claims.agentVersionId().equals(task.agentVersionId())) {
            throw new DispatchTokenInvalidException("Dispatch token agent version " + claims.agentVersionId()
                    + " does not match task " + taskId + " assignment " + task.agentVersionId());
        }
        if (task.status() != TaskStatus.AWAITING_AGENT_RESPONSE) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " is " + task.status() + "; it is not awaiting a response to a rejection");
        }
        RejectionModel rejection = requireLatest(taskId);
        Instant now = clock.instant();
        RejectionModel responded = rejection.respond(response.action(), response.reason(), now, windows);

        switch (response.action()) {
            case REVISE -> revise(task, rejection, responded, response);
            case CONCEDE -> concede(task, responded);
            case STAND_BY -> {
                rejectionRepository.save(responded);
                taskRepository.save(task.awaitClientDecision());
                log.info("Task {}: the Agent stood by its result; the Client decides until {}",
                        taskId, responded.decisionDeadline());
            }
        }
    }

    private void revise(TaskModel task, RejectionModel rejection, RejectionModel responded,
                        AgentRejectionResponseInfo response) {
        boolean noPayload = response.resultPayloadJson() == null || response.resultPayloadJson().isBlank();
        boolean noUrl = response.resultUrl() == null || response.resultUrl().isBlank();
        if (noPayload && noUrl) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "A revision needs the revised result: resultPayloadJson or resultUrl");
        }
        TaskResultModel previous = task.result();
        if (previous != null) {
            rejectionRepository.recordReplacedResult(rejection.id(), previous.resultPayloadJson(), previous.resultUrl());
        }
        rejectionRepository.save(responded);
        TaskResultModel revised = TaskResultModel.record(task.id(), "COMPLETED",
                noPayload ? "{}" : response.resultPayloadJson(), noUrl ? null : response.resultUrl());
        TaskModel received = task.revise(revised);
        taskRepository.save(received);
        // The revision passes the same gate as the first result: back to review (with a fresh review
        // window), or a spec violation with a refund.
        TaskModel gated = validationAppService.validateAndGate(received);
        log.info("Task {}: the Agent revised its result -> {}", task.id(), gated.status());
    }

    private void concede(TaskModel task, RejectionModel responded) {
        rejectionRepository.save(responded);
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.REJECTION_CONCEDED);
        taskRepository.save(task.concede());
        log.info("Task {}: the Agent conceded the rejection; the Client is refunded", task.id());
    }

    @Override
    public UUID requestArbitration(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .filter(t -> t.clientId().equals(clientId)) // Invariant #5: a foreign task is a missing one
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (task.status() != TaskStatus.AWAITING_CLIENT_DECISION) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Task " + taskId + " is " + task.status()
                    + "; arbitration can be requested only after the Agent stood by its result");
        }
        RejectionModel decided = requireLatest(taskId).decide(ClientDecision.ARBITRATE, clock.instant());
        rejectionRepository.save(decided);
        TaskModel disputed = task.requestArbitration();
        taskRepository.save(disputed);
        // The Client's frozen escrow is the security: no deposit or bond is taken (D17′, D20).
        disputeAppService.openDispute(disputed, clientId, decided.reasonCategory());
        log.info("Task {}: the Client requested arbitration of the stood-by result", taskId);
        return taskId;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RejectionModel> findLatest(UUID taskId) {
        return rejectionRepository.findLatestByTaskId(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> responseExpiredTaskIds() {
        return rejectionRepository.findTaskIdsResponseExpired(clock.instant());
    }

    @Override
    public void standBySilentlyOne(UUID taskId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.AWAITING_AGENT_RESPONSE) {
            return; // the Agent responded, or an overlapping pass got here first
        }
        RejectionModel rejection = requireLatest(taskId);
        Instant now = clock.instant();
        if (!rejection.isAwaitingResponse() || !rejection.responseWindowClosed(now)) {
            return;
        }
        RejectionModel stoodBy = rejection.standBySilently(now, windows);
        rejectionRepository.save(stoodBy);
        taskRepository.save(task.awaitClientDecision());
        log.info("Task {}: no response from the Agent by {}; counted as standing by. The Client decides until {}",
                taskId, rejection.responseDeadline(), stoodBy.decisionDeadline());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> decisionExpiredTaskIds() {
        return rejectionRepository.findTaskIdsDecisionExpired(clock.instant());
    }

    @Override
    public void acceptSilentlyOne(UUID taskId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.AWAITING_CLIENT_DECISION) {
            return;
        }
        RejectionModel rejection = requireLatest(taskId);
        Instant now = clock.instant();
        if (!rejection.isAwaitingDecision() || !rejection.decisionWindowClosed(now)) {
            return;
        }
        rejectionRepository.save(rejection.acceptSilently(now));
        acceptedSettlement.settle(task);
        log.info("Task {}: no decision from the Client by {}; the stood-by result is accepted",
                taskId, rejection.decisionDeadline());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> noticePendingTaskIds() {
        return rejectionRepository.findTaskIdsNoticePending(clock.instant(), MAX_NOTICE_ATTEMPTS);
    }

    @Override
    public void deliverNotice(UUID taskId) {
        TaskModel task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.status() != TaskStatus.AWAITING_AGENT_RESPONSE) {
            return;
        }
        RejectionModel rejection = rejectionRepository.findLatestByTaskId(taskId).orElse(null);
        Instant now = clock.instant();
        if (rejection == null || !rejection.isAwaitingResponse() || rejection.responseWindowClosed(now)) {
            return;
        }
        String webhookUrl = agentRepository.findWebhookUrlByVersionId(task.agentVersionId()).orElse(null);
        if (webhookUrl == null) {
            log.warn("Task {}: no webhook for version {}; the rejection notice cannot be sent",
                    taskId, task.agentVersionId());
            rejectionRepository.recordNoticeAttempt(rejection.id(), null);
            return;
        }
        // A token of its own, valid until the response window closes (+ the callback margin): the
        // WorkOrder's token may already have expired with the execution deadline.
        String token = dispatchTokenService.issue(taskId, task.agentVersionId(), rejection.responseDeadline());
        String secret = agentRepository.findSigningSecretByVersionId(task.agentVersionId()).orElse(null);
        RejectionNotice notice = new RejectionNotice(taskId, task.agentVersionId(), rejection.round(),
                rejection.reasonCategory().name(), rejection.reason(), rejection.responseDeadline(),
                rejection.revisionAllowed(),
                publicBaseUrl + "/api/agent-callbacks/" + taskId + "/rejection-response",
                task.title(), task.description(), outputSpecJsonMapper.toJson(task.outputDescription()));
        boolean delivered = agentNoticePort.sendRejectionNotice(webhookUrl, token, secret, notice);
        rejectionRepository.recordNoticeAttempt(rejection.id(), delivered ? now : null);
        if (delivered) {
            log.info("Task {}: rejection notice (round {}) delivered", taskId, rejection.round());
        }
    }

    private RejectionModel requireLatest(UUID taskId) {
        return rejectionRepository.findLatestByTaskId(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Task " + taskId + " has no rejection round"));
    }
}
