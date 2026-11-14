package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutionPortImplTest {

    @Mock TaskRepository taskRepository;
    @Mock SettlementWriteAppService settlementWriteAppService;
    @Mock ReputationWriteAppService reputationWriteAppService;
    @Mock TaskWriteAppService taskWriteAppService;
    @Mock AgentRepository agentRepository;
    @Mock RoutingAppService routingAppService;

    private TaskExecutionPortImpl port() {
        return new TaskExecutionPortImpl(taskRepository, settlementWriteAppService, reputationWriteAppService,
                taskWriteAppService, agentRepository, routingAppService);
    }

    private void locked(TaskModel task) {
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
    }

    private void pinned(TaskModel task, boolean pinned) {
        when(taskRepository.findPinnedAgentVersionId(task.id()))
                .thenReturn(pinned ? Optional.of(task.agentVersionId()) : Optional.empty());
    }

    private void available(TaskModel task, boolean available) {
        when(agentRepository.findCandidateByVersionId(task.agentVersionId()))
                .thenReturn(available ? Optional.of(mock(AgentCandidate.class)) : Optional.empty());
    }

    private TaskModel queuedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID());
    }

    // --- Availability before each send (#58) ---

    @Test
    void aQueuedTaskWhoseAgentIsStillAvailableIsSent() {
        TaskModel task = queuedTask();
        locked(task);
        available(task, true);

        assertThat(port().prepareDispatch(task.id(), task.agentVersionId())).isTrue();

        verifyNoInteractions(taskWriteAppService, routingAppService, reputationWriteAppService);
    }

    @Test
    void aRoutedTaskWhoseAgentIsUnavailableAtDispatchFailsOverWithoutAReputationEvent() {
        TaskModel task = queuedTask();
        locked(task);
        available(task, false);
        pinned(task, false);

        assertThat(port().prepareDispatch(task.id(), task.agentVersionId())).isFalse();

        verify(taskWriteAppService).appendDispatchAttempt(task.id(), task.agentVersionId(),
                DispatchOutcome.UNAVAILABLE);
        verify(routingAppService).failOver(task.id());
        verifyNoInteractions(reputationWriteAppService);
    }

    @Test
    void aPinnedTaskWhoseAgentIsUnavailableAtDispatchIsCancelledWithoutAReputationEvent() {
        TaskModel task = queuedTask();
        locked(task);
        available(task, false);
        pinned(task, true);

        assertThat(port().prepareDispatch(task.id(), task.agentVersionId())).isFalse();

        verify(taskWriteAppService).cancelUnavailableAtDispatch(task.id(), task.agentVersionId());
        verifyNoInteractions(routingAppService, reputationWriteAppService);
    }

    @Test
    void aMessageForAVersionTheTaskNoLongerWaitsOnIsDroppedWithoutCheckingTheAgent() {
        TaskModel task = queuedTask();
        locked(task);

        assertThat(port().prepareDispatch(task.id(), UUID.randomUUID())).isFalse();

        verifyNoInteractions(agentRepository, taskWriteAppService, routingAppService);
    }

    @Test
    void aMissingTaskIsNotSent() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());

        assertThat(port().prepareDispatch(taskId, UUID.randomUUID())).isFalse();
    }

    // --- Routed failover (#78) ---

    @Test
    void aDeadLetterForARoutedQueuedTaskRecordsTheFailureAndFailsOver() {
        TaskModel task = queuedTask();
        locked(task);
        pinned(task, false);

        port().markFailed(task.id(), task.agentVersionId());

        verify(reputationWriteAppService).recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.EXECUTION_FAILED);
        verify(taskWriteAppService).appendDispatchAttempt(task.id(), task.agentVersionId(),
                DispatchOutcome.UNREACHABLE);
        verify(routingAppService).failOver(task.id());
        verify(taskWriteAppService, never()).failWithRefund(any(), any());
    }

    @Test
    void aBusyReplyForARoutedTaskFailsOverWithoutAReputationEvent() {
        TaskModel task = queuedTask();
        locked(task);
        pinned(task, false);

        port().declineAsBusy(task.id(), task.agentVersionId());

        verify(taskWriteAppService).appendDispatchAttempt(task.id(), task.agentVersionId(), DispatchOutcome.BUSY);
        verify(routingAppService).failOver(task.id());
        verify(taskWriteAppService, never()).failWithRefund(any(), any());
        verifyNoInteractions(reputationWriteAppService);
    }

    @Test
    void aBusyReplyForAPinnedTaskEndsItWithAgentBusyAndNoFailover() {
        TaskModel task = queuedTask();
        locked(task);
        pinned(task, true);

        port().declineAsBusy(task.id(), task.agentVersionId());

        verify(taskWriteAppService).appendDispatchAttempt(task.id(), task.agentVersionId(), DispatchOutcome.BUSY);
        verify(taskWriteAppService).failWithRefund(task.id(), FailureReason.AGENT_BUSY);
        verifyNoInteractions(routingAppService, reputationWriteAppService);
    }

    @Test
    void aDeadLetterForAVersionTheTaskMovedAwayFromIsANoOp() {
        // The task already failed over: a redelivered dead letter for the old Agent must not
        // touch the new assignment.
        TaskModel task = queuedTask();
        locked(task);

        port().markFailed(task.id(), UUID.randomUUID());

        verifyNoInteractions(reputationWriteAppService, taskWriteAppService, routingAppService);
    }

    @Test
    void aDeadLetterForAPinnedTaskRecordsExecutionFailedAndEndsItAgentUnreachable() {
        TaskModel task = queuedTask();
        locked(task);
        pinned(task, true);

        port().markFailed(task.id(), task.agentVersionId());

        verify(reputationWriteAppService).recordOutcome(task.id(), task.agentVersionId(), task.clientId(),
                ReputationEventType.EXECUTION_FAILED);
        verify(taskWriteAppService).failWithRefund(task.id(), FailureReason.AGENT_UNREACHABLE);
        verifyNoInteractions(routingAppService);
    }

    @Test
    void aDeadLetterForAnExecutingTaskEndsItEvenWhenRouted() {
        // Failover applies only before the Agent started.
        TaskModel task = queuedTask().markExecuting();
        locked(task);
        pinned(task, false);

        port().markFailed(task.id(), task.agentVersionId());

        verify(taskWriteAppService).failWithRefund(task.id(), FailureReason.AGENT_UNREACHABLE);
        verifyNoInteractions(routingAppService);
    }

    @Test
    void duplicateMarkFailedIsANoOp() {
        TaskModel failed = queuedTask().markExecuting().markFailed(FailureReason.AGENT_UNREACHABLE);
        locked(failed);

        port().markFailed(failed.id(), failed.agentVersionId());

        verifyNoInteractions(settlementWriteAppService, reputationWriteAppService, taskWriteAppService,
                routingAppService);
    }

    @Test
    void aBusyReplyForAVersionTheTaskNoLongerWaitsOnIsANoOp() {
        TaskModel task = queuedTask();
        locked(task);

        port().declineAsBusy(task.id(), UUID.randomUUID());

        verifyNoInteractions(reputationWriteAppService, taskWriteAppService, routingAppService);
    }

    @Test
    void aBusyReplyForAnExecutingTaskIsANoOp() {
        TaskModel task = queuedTask().markExecuting();
        locked(task);

        port().declineAsBusy(task.id(), task.agentVersionId());

        verifyNoInteractions(reputationWriteAppService, taskWriteAppService, routingAppService);
    }

    // --- Execution transitions ---

    @Test
    void markExecutingTransitionsQueuedToExecuting() {
        TaskModel task = queuedTask();
        locked(task);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markExecuting(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markTimedOutTransitionsExecutingToTimedOutAndRefunds() {
        TaskModel executing = queuedTask().markExecuting();
        locked(executing);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        port().markTimedOut(executing.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.TIMED_OUT);
        verify(settlementWriteAppService).settleRejected(executing.id(), executing.clientId(), executing.budget());
    }

    @Test
    void duplicateMarkTimedOutIsANoOp() {
        TaskModel timedOut = queuedTask().markExecuting().markTimedOut();
        locked(timedOut);

        port().markTimedOut(timedOut.id());

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }

    @Test
    void throwsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> port().markExecuting(taskId))
                .isInstanceOf(DomainException.class);
    }
}
