package com.hireai.application.biz.ledger.settlement.impl;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementDomainService;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.ledger.wallet.repository.WalletRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementWriteAppServiceImplTest {

    @Mock WalletRepository walletRepository;
    @Mock SettlementDomainService settlementDomainService;
    @Mock SettlementRepository settlementRepository;

    SettlementWriteAppServiceImpl service;

    final UUID clientId = UUID.randomUUID();
    final UUID builderId = UUID.randomUUID();
    final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SettlementWriteAppServiceImpl(
                walletRepository, settlementDomainService, settlementRepository);
    }

    @Test
    void settleAcceptedSavesBothWalletsAndRecordsSettlement() {
        WalletModel clientWallet = WalletModel.openFor(clientId);
        WalletModel builderWallet = WalletModel.openFor(builderId);
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.of(builderWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SettlementBreakdown expected = new SettlementBreakdown(Money.of("17.00"), Money.of("3.00"));
        when(settlementDomainService.settleAcceptance(eq(clientWallet), eq(builderWallet),
                eq(Money.of("20.00")), eq(taskId), any()))
                .thenReturn(expected);

        SettlementBreakdown result = service.settleAccepted(taskId, clientId, builderId, Money.of("20.00"));

        assertThat(result).isEqualTo(expected);
        verify(walletRepository, times(2)).save(any());
        verify(settlementRepository).save(argThat(s -> s.type() == SettlementType.ACCEPT));
    }

    @Test
    void settleAcceptedOpensBuilderWalletWhenAbsent() {
        WalletModel clientWallet = WalletModel.openFor(clientId);
        WalletModel newBuilderWallet = WalletModel.openFor(builderId);
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.findByUserId(builderId)).thenReturn(Optional.empty());
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(any(), any(), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.settleAccepted(taskId, clientId, builderId, Money.of("20.00"));

        // three saves: opening the builder wallet, then client + builder after settlement
        verify(walletRepository, times(3)).save(any());
    }

    @Test
    void selfSettleSavesOneWallet() {
        WalletModel shared = WalletModel.openFor(clientId);
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(shared));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementDomainService.settleAcceptance(eq(shared), eq(shared), any(), any(), any()))
                .thenReturn(new SettlementBreakdown(Money.of("17.00"), Money.of("3.00")));

        service.settleAccepted(taskId, clientId, clientId, Money.of("20.00"));

        verify(walletRepository, times(1)).save(any());
    }

    @Test
    void settleRejectedRefundsAndRecordsRejectSettlement() {
        WalletModel clientWallet = WalletModel.openFor(clientId);
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.of(clientWallet));
        when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.settleRejected(taskId, clientId, Money.of("20.00"));

        verify(settlementDomainService).settleRejection(eq(clientWallet), eq(Money.of("20.00")),
                eq(taskId), any());
        verify(settlementRepository).save(argThat(s -> s.type() == SettlementType.REJECT));
    }

    @Test
    void requireWalletMissingThrowsNotFound() {
        when(walletRepository.findByUserId(clientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.settleAccepted(taskId, clientId, builderId, Money.of("20.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }
}
