package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskWriteAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock TaskSubmitDomainService taskSubmitDomainService;
    @Mock WalletWriteAppService walletWriteAppService;
    @Mock ApplicationEventPublisher eventPublisher;

    private TaskWriteAppServiceImpl service() {
        return new TaskWriteAppServiceImpl(taskRepository, taskSubmitDomainService,
                walletWriteAppService, eventPublisher);
    }

    private TaskModel submittedTask() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    @Test
    void assignAndQueueTransitionsAndSaves() {
        TaskModel task = submittedTask();
        UUID agentVersionId = UUID.randomUUID();
        Instant deadline = Instant.now().plusSeconds(120);
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().assignAndQueue(task.id(), agentVersionId, deadline);

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(captor.getValue().agentVersionId()).isEqualTo(agentVersionId);
        verify(taskRepository).stampExecutionDeadline(task.id(), deadline);
    }

    @Test
    void markAwaitingCapacityTransitionsAndSaves() {
        TaskModel task = submittedTask();
        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().markAwaitingCapacity(task.id());

        ArgumentCaptor<TaskModel> captor = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TaskStatus.AWAITING_CAPACITY);
    }

    @Test
    void assignAndQueueThrowsNotFoundForMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().assignAndQueue(taskId, UUID.randomUUID(), Instant.now().plusSeconds(120)))
                .isInstanceOf(DomainException.class);
    }
}
