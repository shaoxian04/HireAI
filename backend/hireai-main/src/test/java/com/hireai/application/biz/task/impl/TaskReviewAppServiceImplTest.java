package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReviewAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock AgentRepository agentRepository;
    @Mock SettlementWriteAppService settlementWriteAppService;

    TaskReviewAppServiceImpl service;

    final UUID clientId = UUID.randomUUID();
    final UUID builderId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskReviewAppServiceImpl(
                taskRepository, agentRepository, settlementWriteAppService);
    }

    private TaskModel resultReceivedTask() {
        TaskModel t = TaskModel.submit(clientId, "t", "d", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(versionId).markExecuting();
        return t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
    }

    @Test
    void acceptSettlesAndSavesTask() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));
        when(settlementWriteAppService.settleAccepted(eq(task.id()), eq(clientId), eq(builderId),
                eq(Money.of("20.00"))))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.accept(task.id(), clientId);

        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
        verify(settlementWriteAppService).settleAccepted(eq(task.id()), eq(clientId), eq(builderId),
                eq(Money.of("20.00")));
    }

    @Test
    void rejectRefundsAndNeverTouchesTheBuilder() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(task.id(), clientId, "not good");

        verify(settlementWriteAppService).settleRejected(eq(task.id()), eq(clientId), eq(Money.of("20.00")));
        verify(agentRepository, never()).findOwnerByVersionId(any());
        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().rejectionReason()).isEqualTo("not good");
    }

    @Test
    void nonOwnerGetsNotFound() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.accept(task.id(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }
}
