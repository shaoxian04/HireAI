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
import org.mockito.ArgumentMatchers;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.acceptance.check.model.AcceptanceEvidence;
import com.hireai.domain.biz.acceptance.check.service.ImageDecoder;
import com.hireai.domain.biz.adjudication.model.ReworkPolicy;
import com.hireai.domain.biz.task.routing.info.ReworkRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class ValidationAppServiceImplTest {

    private final ValidationDomainService domain = mock(ValidationDomainService.class);
    private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final SettlementWriteAppService settlement = mock(SettlementWriteAppService.class);
    private final com.hireai.domain.biz.offering.agent.repository.AgentRepository agentRepository =
            mock(com.hireai.domain.biz.offering.agent.repository.AgentRepository.class);
    private final com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository apiKeyTaskRepository =
            mock(com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository.class);
    private final com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService webhookOutbox =
            mock(com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService.class);
    private final com.hireai.application.biz.reputation.ReputationWriteAppService reputationWrite =
            mock(com.hireai.application.biz.reputation.ReputationWriteAppService.class);
    private final DeliverySnapshotAppService snapshots = mock(DeliverySnapshotAppService.class);
    private final RoutingAppService routing = mock(RoutingAppService.class);
    private final ImageDecoder decoder = mock(ImageDecoder.class);
    private final ValidationAppServiceImpl svc = new ValidationAppServiceImpl(domain, snapshots, reports, tasks, settlement,
            agentRepository, apiKeyTaskRepository, webhookOutbox, reputationWrite,
            new com.hireai.domain.biz.task.model.ReviewWindow(java.time.Duration.ofHours(24)),
            new ReworkPolicy(2), routing, decoder,
            mock(com.hireai.application.biz.acceptance.aireview.AiReviewAppService.class), java.time.Clock.systemUTC());

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
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1))).thenReturn(passingReport(task.id()));
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
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1)))
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
        when(task.contract()).thenReturn(new OutputSpec(OutputFormat.TEXT, null, null).toContract());
        TaskModel pending = mock(TaskModel.class);
        TaskModel resolved = mock(TaskModel.class);
        when(task.passValidation()).thenReturn(pending);
        when(pending.accept()).thenReturn(resolved);
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), anyInt())).thenReturn(passingReport(taskId));
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
        when(task.contract()).thenReturn(new OutputSpec(OutputFormat.TEXT, null, null).toContract());
        TaskModel pending = mock(TaskModel.class);
        when(task.passValidation()).thenReturn(pending);
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), anyInt())).thenReturn(passingReport(taskId));
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
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(task);

        verify(webhookOutbox).enqueueFailed(any(), eq("SPEC_VIOLATION"));
    }

    @Test
    void aTaskIsJudgedAgainstTheContractItFroze() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1))).thenReturn(passingReport(task.id()));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(task);

        verify(domain).judge(eq(task.contract()), eq(task.result()), ArgumentMatchers.<AcceptanceEvidence>argThat(e -> e.delivery() == null), eq(1));
    }

    @Test
    void aTaskIsJudgedAgainstItsFrozenContract() {
        TaskModel legacy = mock(TaskModel.class); // V55 gave every Task a Contract
        OutputSpec spec = new OutputSpec(OutputFormat.TEXT, null, null);
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "\"hi\"", null);
        when(legacy.contract()).thenReturn(spec.toContract());
        when(legacy.result()).thenReturn(result);
        when(legacy.passValidation()).thenReturn(mock(TaskModel.class));
        when(domain.judge(eq(spec.toContract()), eq(result), ArgumentMatchers.<AcceptanceEvidence>argThat(e -> e.delivery() == null), eq(1))).thenReturn(passingReport(UUID.randomUUID()));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(legacy);

        verify(domain).judge(eq(spec.toContract()), eq(result), ArgumentMatchers.<AcceptanceEvidence>argThat(e -> e.delivery() == null), eq(1));
    }

    @Test
    void aFileResultIsFrozenBeforeItIsJudgedAndTheChecksSeeTheSnapshot() {
        TaskModel task = mock(TaskModel.class);
        OutputSpec spec = new OutputSpec(OutputFormat.FILE, null, null);
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", "https://cdn.example.com/a.png");
        when(task.contract()).thenReturn(spec.toContract());
        when(task.result()).thenReturn(result);
        when(task.passValidation()).thenReturn(mock(TaskModel.class));
        when(domain.needsDelivery(spec.toContract(), result)).thenReturn(true);
        DeliveryOutcome frozen = new DeliveryOutcome.Unavailable("the link answered 404");
        when(snapshots.capture(spec.toContract(), result)).thenReturn(frozen);
        when(domain.judge(eq(spec.toContract()), eq(result), ArgumentMatchers.<AcceptanceEvidence>argThat(e -> e.delivery() == frozen), eq(1))).thenReturn(passingReport(UUID.randomUUID()));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(task);

        verify(domain).judge(eq(spec.toContract()), eq(result), ArgumentMatchers.<AcceptanceEvidence>argThat(e -> e.delivery() == frozen), eq(1));
    }

    @Test
    void aResultWithNothingToFetchTakesNoSnapshot() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1))).thenReturn(passingReport(task.id()));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.empty());

        svc.validateAndGate(task);

        verifyNoInteractions(snapshots);
    }

    @Test
    void aRejectedResultGoesBackToTheSameAgentWithTheFailingChecksWhileReworksRemain() {
        TaskModel task = resultReceivedTask();
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));
        when(reports.countRejectedByTaskId(task.id())).thenReturn(2);
        when(routing.redispatchForRework(eq(task.id()), eq(task.agentVersionId()), any())).thenReturn(true);
        when(tasks.findById(task.id())).thenReturn(Optional.of(task));

        svc.validateAndGate(task);

        verify(routing).redispatchForRework(eq(task.id()), eq(task.agentVersionId()),
                eq(new ReworkRequest(2, 2, List.of(new ReworkRequest.FailedCheck("FORMAT_TEXT_NON_EMPTY", "empty payload")))));
        verifyNoInteractions(settlement, reputationWrite, webhookOutbox);
    }

    @Test
    void aResultRejectedAfterTheLastReworkIsASpecViolation() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));
        when(reports.countRejectedByTaskId(task.id())).thenReturn(3);

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
        verifyNoInteractions(routing);
        verify(settlement).settleRejected(eq(task.id()), eq(task.clientId()), eq(task.budget()));
    }

    @Test
    void aResultSentToReviewWaitsForTheClientEvenOnTheApiChannel() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.judge(ArgumentMatchers.<Contract>any(), any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(CheckResult.review("SELF_REPORTED_ANOMALY", "two brand colours"))));
        when(apiKeyTaskRepository.findApiKeyIdByTask(any())).thenReturn(Optional.of(UUID.randomUUID()));

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
        verifyNoInteractions(settlement);
    }
}
