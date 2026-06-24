package com.hireai.application.biz.task.impl;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.biz.wallet.service.SettlementDomainService;
import com.hireai.domain.shared.exception.DomainException;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReviewAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock AgentRepository agentRepository;
    @Mock WalletRepository walletRepository;
    @Mock SettlementDomainService settlementDomainService;

    TaskReviewAppServiceImpl service;

    final UUID clientId = UUID.randomUUID();
    final UUID builderId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskReviewAppServiceImpl(
                taskRepository, agentRepository, walletRepository, settlementDomainService);
    }

    private TaskModel resultReceivedTask() {
        TaskModel t = TaskModel.submit(clientId, "t", "d", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(versionId).markExecuting();
        return t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
    }

    @Test
    void acceptSettlesAndSavesTaskAndBothWallets() {
        TaskModel task = resultReceivedTask();
        WalletModel clientWallet = WalletModel.openFor(clientId);
        WalletModel builderWallet = WalletModel.openFor(builderId);
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.of(builderWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(eq(clientWallet), eq(builderWallet),
                eq(Money.of("20.00")), eq(task.id()), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
        verify(walletRepository, times(2)).save(any());
    }

    @Test
    void acceptOpensBuilderWalletWhenAbsent() {
        TaskModel task = resultReceivedTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(builderId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(WalletModel.openFor(clientId)));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(any(), any(), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        // three saves: opening the builder wallet, then client + builder after settlement
        verify(walletRepository, times(3)).save(any());
    }

    @Test
    void selfAcceptUsesOneWalletAndSavesItOnce() {
        TaskModel task = resultReceivedTask();
        WalletModel shared = WalletModel.openFor(clientId);
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(versionId)).thenReturn(Optional.of(clientId));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(shared));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(eq(shared), eq(shared), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.accept(task.id(), clientId);

        verify(walletRepository, times(1)).save(any());
    }

    @Test
    void rejectRefundsAndNeverTouchesTheBuilder() {
        TaskModel task = resultReceivedTask();
        WalletModel clientWallet = WalletModel.openFor(clientId);
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(task.id(), clientId, "not good");

        verify(settlementDomainService).settleRejection(eq(clientWallet), eq(Money.of("20.00")),
                eq(task.id()), any());
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
