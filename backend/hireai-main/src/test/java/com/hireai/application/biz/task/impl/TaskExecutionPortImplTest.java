package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
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

    private TaskExecutionPortImpl port() {
        return new TaskExecutionPortImpl(taskRepository, settlementWriteAppService);
    }

    private TaskModel queuedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general")
                .assignAndQueue(UUID.randomUUID());
    }

    @Test
    void markExecutingTransitionsQueuedToExecuting() {
        TaskModel task = queuedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
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

        port().markFailed(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void throwsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

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
        port.markFailed(taskId);

        // verify
        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.FAILED);
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
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
                .markFailed();

        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(failedTask));

        TaskExecutionPortImpl port = port();
        port.markFailed(taskId);

        // verify: no save, no settlement call
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
}
