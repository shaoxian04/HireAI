package com.hireai.application.biz.task.impl;

import com.hireai.domain.biz.task.enums.FailureReason;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.application.biz.task.routing.RoutingAppService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutionPortImplTest {

    @Mock TaskRepository taskRepository;
    @Mock SettlementWriteAppService settlementWriteAppService;
    @Mock ReputationWriteAppService reputationWriteAppService;
    @Mock WebhookOutboxAppService webhookOutboxAppService;
    @Mock DispatchAttemptRepository dispatchAttemptRepository;
    @Mock RoutingAppService routingAppService;

    private TaskExecutionPortImpl port() {
        return new TaskExecutionPortImpl(taskRepository, settlementWriteAppService,
                reputationWriteAppService, webhookOutboxAppService, dispatchAttemptRepository,
                Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC), routingAppService);
    }

    private void pinned(TaskModel task) {
        when(taskRepository.findPinnedAgentVersionId(task.id())).thenReturn(Optional.of(task.agentVersionId()));
    }

    // --- Routed failover (#78) ---

    @Test
    void aDeadLetterForARoutedQueuedTaskRecordsTheFailureAndFailsOver() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.findPinnedAgentVersionId(task.id())).thenReturn(Optional.empty());

        port().markFailed(task.id(), task.agentVersionId());

        verify(reputationWriteAppService).recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.EXECUTION_FAILED);
        verify(dispatchAttemptRepository).append(org.mockito.ArgumentMatchers.argThat(a ->
                a.outcome() == DispatchOutcome.UNREACHABLE && a.agentVersionId().equals(task.agentVersionId())));
        verify(routingAppService).failOver(task.id());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, webhookOutboxAppService);
    }

    @Test
    void aBusyReplyForARoutedTaskFailsOverWithoutAReputationEvent() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.findPinnedAgentVersionId(task.id())).thenReturn(Optional.empty());

        port().declineAsBusy(task.id(), task.agentVersionId());

        verify(dispatchAttemptRepository).append(org.mockito.ArgumentMatchers.argThat(a ->
                a.outcome() == DispatchOutcome.BUSY));
        verify(routingAppService).failOver(task.id());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, webhookOutboxAppService, reputationWriteAppService);
    }

    @Test
    void aBusyReplyForAPinnedTaskEndsItWithoutFailover() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        pinned(task);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().declineAsBusy(task.id(), task.agentVersionId());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().failureReason()).isEqualTo(FailureReason.AGENT_BUSY);
        verifyNoInteractions(routingAppService, reputationWriteAppService);
    }

    @Test
    void aDeadLetterForAVersionTheTaskMovedAwayFromIsANoOp() {
        // The task already failed over: a redelivered dead letter for the old Agent must not
        // touch the new assignment.
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));

        port().markFailed(task.id(), UUID.randomUUID());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, reputationWriteAppService, webhookOutboxAppService,
                dispatchAttemptRepository, routingAppService);
    }

    private TaskModel queuedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID());
    }

    @Test
    void markExecutingTransitionsQueuedToExecuting() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markExecuting(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markTimedOutTransitionsQueuedToTimedOutAndRefunds() {
        // FIX 4: markTimedOut now mirrors markFailed's guarded+refund shape and loads under a row lock.
        UUID taskId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Money budget = Money.of("100.00");

        TaskModel executingTask = TaskModel.submit(clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID())
                .markExecuting();

        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(executingTask));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markTimedOut(taskId);

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.TIMED_OUT);
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void duplicateMarkTimedOutIsANoOp() {
        UUID taskId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Money budget = Money.of("100.00");

        TaskModel timedOutTask = TaskModel.submit(clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .markTimedOut();

        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(timedOutTask));

        port().markTimedOut(taskId);

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }

    @Test
    void markFailedTransitionsQueuedToFailed() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        pinned(task);

        port().markFailed(task.id(), task.agentVersionId());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void throwsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port().markExecuting(taskId))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markFailedRefundsTheClientEscrow() {
        // arrange: findById -> EXECUTING task with clientId + budget
        UUID taskId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Money budget = Money.of("100.00");

        TaskModel executingTask = TaskModel.submit(clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID())
                .markExecuting();

        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(executingTask));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TaskExecutionPortImpl port = port();
        port.markFailed(taskId, executingTask.agentVersionId());

        // verify
        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void markFailedRecordsExecutionFailedAgainstTheAgentAndNotifiesTheClient() {
        UUID clientId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        TaskModel queued = TaskModel.submit(clientId, "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(versionId);
        when(taskRepository.findByIdForUpdate(queued.id())).thenReturn(Optional.of(queued));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        pinned(queued);

        port().markFailed(queued.id(), versionId);

        verify(reputationWriteAppService).recordOutcome(queued.id(), versionId, clientId,
                ReputationEventType.EXECUTION_FAILED);
        ArgumentCaptor<TaskModel> failed = ArgumentCaptor.forClass(TaskModel.class);
        verify(webhookOutboxAppService).enqueueFailed(failed.capture(), org.mockito.ArgumentMatchers.eq("FAILED"));
        assertThat(failed.getValue().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void duplicateMarkFailedIsANoOp() {
        // arrange: findById -> task already FAILED
        UUID taskId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Money budget = Money.of("100.00");

        TaskModel failedTask = TaskModel.submit(clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .markFailed(FailureReason.AGENT_UNREACHABLE);

        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(failedTask));

        TaskExecutionPortImpl port = port();
        port.markFailed(taskId, failedTask.agentVersionId());

        // verify: no save, no settlement, reputation, webhook or second UNREACHABLE attempt
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, reputationWriteAppService, webhookOutboxAppService,
                dispatchAttemptRepository);
    }

    @Test
    void aBusyReplyForAVersionTheTaskNoLongerWaitsOnIsANoOp() {
        TaskModel task = queuedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));

        port().declineAsBusy(task.id(), UUID.randomUUID());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, reputationWriteAppService, webhookOutboxAppService,
                dispatchAttemptRepository);
    }

    @Test
    void aBusyReplyForAnExecutingTaskIsANoOp() {
        TaskModel task = queuedTask().markExecuting();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));

        port().declineAsBusy(task.id(), task.agentVersionId());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService, reputationWriteAppService, webhookOutboxAppService);
    }
}