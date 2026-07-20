package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.webhook.WebhookOutboxAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskWriteAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock TaskSubmitDomainService taskSubmitDomainService;
    @Mock WalletWriteAppService walletWriteAppService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock SettlementWriteAppService settlementWriteAppService;
    @Mock WebhookOutboxAppService webhookOutboxAppService;

    private TaskWriteAppServiceImpl service() {
        return new TaskWriteAppServiceImpl(taskRepository, taskSubmitDomainService,
                walletWriteAppService, eventPublisher, settlementWriteAppService, webhookOutboxAppService);
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

    @Test
    void directBookingPersistsThePinnedAgentVersion() {
        UUID versionId = UUID.randomUUID();
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(UUID.randomUUID(), "title", "desc",
                Money.of("10.00"), new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
        TaskModel submittedTask = submittedTask();
        when(taskSubmitDomainService.submit(submitInfo)).thenReturn(submittedTask);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().submitDirectlyBooked(submitInfo, versionId);

        verify(taskRepository).pinAgentVersion(submittedTask.id(), versionId);
    }

    @Test
    void openSubmitDoesNotPin() {
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(UUID.randomUUID(), "title", "desc",
                Money.of("10.00"), new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
        UUID taskId = UUID.randomUUID();
        TaskModel submittedTask = TaskModel.submit(taskId, "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
        when(taskSubmitDomainService.submit(submitInfo)).thenReturn(submittedTask);
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().submit(submitInfo);

        verify(taskRepository, never()).pinAgentVersion(any(), any());
    }

    @Test
    void registerMatchAttemptIncrementsAndReturnsNewCount() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.matchAttempts(taskId)).thenReturn(2);

        assertThat(service().registerMatchAttempt(taskId)).isEqualTo(2);

        verify(taskRepository).incrementMatchAttempts(taskId);
    }

    @Test
    void cancelRefundsFullBudgetAndCancels() {
        UUID clientId = UUID.randomUUID();
        Money budget = Money.of("100.00");
        TaskModel task = TaskModel.submit(clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
        UUID taskId = task.id();
        task = task.markAwaitingCapacity();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service().cancelAwaitingCapacityWithRefund(taskId);

        verify(taskRepository).save(argThat(t -> t.status() == TaskStatus.CANCELLED));
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
        verify(webhookOutboxAppService).enqueueFailed(any(), eq("CANCELLED"));
    }

    @Test
    void cancelIsANoOpWhenTaskAlreadyLeftAwaitingCapacity() {
        UUID taskId = UUID.randomUUID();
        TaskModel task = submittedTask();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service().cancelAwaitingCapacityWithRefund(taskId);

        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
}
