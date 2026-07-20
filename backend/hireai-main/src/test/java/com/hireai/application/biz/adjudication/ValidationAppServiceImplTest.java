package com.hireai.application.biz.adjudication;

import com.hireai.application.biz.adjudication.validation.impl.ValidationAppServiceImpl;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ValidationAppServiceImplTest {

    private final ValidationDomainService domain = mock(ValidationDomainService.class);
    private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final SettlementWriteAppService settlement = mock(SettlementWriteAppService.class);
    private final com.hireai.domain.biz.offering.agent.repository.AgentRepository agentRepository =
            mock(com.hireai.domain.biz.offering.agent.repository.AgentRepository.class);
    private final com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository apiKeyTaskRepository =
            mock(com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository.class);
    private final com.hireai.application.biz.webhook.WebhookOutboxAppService webhookOutbox =
            mock(com.hireai.application.biz.webhook.WebhookOutboxAppService.class);
    private final ValidationAppServiceImpl svc = new ValidationAppServiceImpl(domain, reports, tasks, settlement,
            agentRepository, apiKeyTaskRepository, webhookOutbox);

    /** Build a RESULT_RECEIVED TaskModel via the canonical transition chain. */
    private TaskModel resultReceivedTask() {
        TaskModel submitted = TaskModel.submit(
                UUID.randomUUID(), "title", "description",
                Money.of("50.00"),
                new OutputSpec(OutputFormat.TEXT, null, "non-empty text"),
                "general");
        TaskResultModel resultModel = TaskResultModel.record(
                submitted.id(), "COMPLETED", "\"hello world\"", null);
        return submitted
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .recordResult(resultModel);
    }

    /** A single passing CheckResult, wrapped as a PASS ValidationReportModel for the given task. */
    private ValidationReportModel passingReport(UUID taskId) {
        return ValidationReportModel.of(taskId, 1,
                List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", true, "non-empty text")));
    }

    @Test
    void passMovesToPendingReviewNoRefund() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.validate(any(), any(), eq(1))).thenReturn(passingReport(task.id()));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
        verify(reports).save(any());
        verify(tasks).save(any());
        verify(settlement, never()).settleRejected(any(), any(), any());
        verify(settlement, never()).settleAccepted(any(), any(), any(), any());
        verify(webhookOutbox, never()).enqueueCompleted(any());
    }

    @Test
    void failMovesToSpecViolationAndRefunds() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.validate(any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
        verify(reports).save(any());
        verify(tasks).save(any());
        verify(settlement).settleRejected(eq(task.id()), eq(task.clientId()), eq(task.budget()));
    }

    @Test
    void apiTaskPassAutoSettlesAndEnqueuesCompleted() {
        UUID taskId = UUID.randomUUID(), clientId = UUID.randomUUID(),
                versionId = UUID.randomUUID(), builderId = UUID.randomUUID();
        // task at RESULT_RECEIVED, api-attributed; report passes.
        TaskModel task = mock(TaskModel.class);
        when(task.id()).thenReturn(taskId);
        when(task.clientId()).thenReturn(clientId);
        when(task.agentVersionId()).thenReturn(versionId);
        when(task.budget()).thenReturn(Money.of("100"));
        TaskModel pending = mock(TaskModel.class);
        TaskModel resolved = mock(TaskModel.class);
        when(task.passValidation()).thenReturn(pending);
        when(pending.accept()).thenReturn(resolved);
        when(domain.validate(any(), any(), anyInt())).thenReturn(passingReport(taskId));
        when(apiKeyTaskRepository.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));

        TaskModel out = svc.validateAndGate(task);

        assertThat(out).isSameAs(resolved);
        verify(settlement).settleAccepted(eq(taskId), eq(clientId), eq(builderId), any());
        verify(tasks).save(resolved);
        verify(webhookOutbox).enqueueCompleted(resolved);
        verify(tasks, never()).save(pending); // did NOT stop at PENDING_REVIEW
    }

    @Test
    void apiTaskPassWithNoAgentOwnerThrowsAndSettlesNothing() {
        // Money safety on the new auto-settle path: if the agent version has no owner, the branch
        // throws BEFORE any settlement/accept/enqueue, so the whole (transactional) callback rolls back.
        UUID taskId = UUID.randomUUID(), versionId = UUID.randomUUID();
        TaskModel task = mock(TaskModel.class);
        when(task.id()).thenReturn(taskId);
        when(task.agentVersionId()).thenReturn(versionId);
        TaskModel pending = mock(TaskModel.class);
        when(task.passValidation()).thenReturn(pending);
        when(domain.validate(any(), any(), anyInt())).thenReturn(passingReport(taskId));
        when(apiKeyTaskRepository.findApiKeyIdByTask(taskId)).thenReturn(Optional.of(UUID.randomUUID()));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.empty()); // no owner

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.validateAndGate(task))
                .isInstanceOf(com.hireai.utility.exception.DomainException.class)
                .hasMessageContaining("owner");
        verify(settlement, never()).settleAccepted(any(), any(), any(), any());
        verify(tasks, never()).save(pending);        // never advanced to RESOLVED
        verify(webhookOutbox, never()).enqueueCompleted(any());
    }

    @Test
    void failEnqueuesFailedSpecViolation() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.validate(any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(task);

        verify(webhookOutbox).enqueueFailed(any(), eq("SPEC_VIOLATION"));
    }
}
