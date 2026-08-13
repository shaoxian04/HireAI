package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ValidationAppServiceImpl implements ValidationAppService {

    private final ValidationDomainService validationDomainService;
    private final ValidationReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;
    private final AgentRepository agentRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final ReputationWriteAppService reputationWriteAppService;

    private static final int FIRST_ATTEMPT = 1; // retry attempts arrive in Plan 2

    @Override
    public TaskModel validateAndGate(TaskModel task) {
        ValidationReportModel report =
                validationDomainService.validate(task.outputSpec(), task.result(), FIRST_ATTEMPT);
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
            log.info("Task {} passed validation -> PENDING_REVIEW", task.id());
            return gated;
        }
        TaskModel gated = task.failValidation();
        taskRepository.save(gated);
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        // The output missed the contract the builder declared (Invariant #4) — a zero-quality
        // sample against Reliability.
        reputationWriteAppService.recordOutcome(task.id(), task.agentVersionId(),
                task.clientId(), ReputationEventType.SPEC_VIOLATION);
        webhookOutboxAppService.enqueueFailed(gated, "SPEC_VIOLATION");
        log.info("Task {} failed validation -> SPEC_VIOLATION (refunded)", task.id());
        return gated;
    }
}
