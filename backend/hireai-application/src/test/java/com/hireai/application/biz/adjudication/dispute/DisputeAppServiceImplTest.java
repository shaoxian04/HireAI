package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.dispute.impl.DisputeAppServiceImpl;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DisputeAppServiceImplTest {

    DisputeRepository disputeRepository;
    TaskRepository taskRepository;
    AgentRepository agentRepository;
    SettlementWriteAppService settlement;
    ArbitrationGateway gateway;
    DisputeAppServiceImpl service;

    UUID clientId = UUID.randomUUID();
    UUID builderId = UUID.randomUUID();
    UUID agentVersionId = UUID.randomUUID();
    TaskModel disputedTask;

    @BeforeEach
    void setUp() {
        disputeRepository = mock(DisputeRepository.class);
        taskRepository = mock(TaskRepository.class);
        agentRepository = mock(AgentRepository.class);
        settlement = mock(SettlementWriteAppService.class);
        gateway = mock(ArbitrationGateway.class);
        service = new DisputeAppServiceImpl(disputeRepository, taskRepository, agentRepository, settlement, gateway);

        when(disputeRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(agentRepository.findOwnerByVersionId(agentVersionId)).thenReturn(Optional.of(builderId));

        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        disputedTask = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "ok", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "mismatch");
        when(taskRepository.findByIdForUpdate(disputedTask.id())).thenReturn(Optional.of(disputedTask));
    }

    @Test
    void openWithSynchronousNotFulfilledRulingRecordsProposal_doesNotSettle() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.NOT_FULFILLED, "no")));

        UUID disputeId = service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);

        // Delayed settlement: a synchronous (stub) ruling is a PROPOSAL — escrow stays held.
        verifyNoInteractions(settlement);
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RULED);
        assertThat(cap.getValue().effectiveRuling()).isPresent();
        assertThat(cap.getValue().effectiveRuling().get().category()).isEqualTo(RulingCategory.NOT_FULFILLED);
        assertThat(disputeId).isNotNull();
        // task not touched — no lock, no resolve
        verify(taskRepository, never()).findByIdForUpdate(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void synchronousPartialRulingRecordsProposal_doesNotSettle() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.PARTIALLY_FULFILLED, "half")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verifyNoInteractions(settlement);
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RULED);
        assertThat(cap.getValue().effectiveRuling().get().category()).isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void synchronousFulfilledRulingRecordsProposal_doesNotSettle() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.FULFILLED, "ok")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verifyNoInteractions(settlement);
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RULED);
        assertThat(cap.getValue().effectiveRuling().get().category()).isEqualTo(RulingCategory.FULFILLED);
    }

    @Test
    void emptyGatewayResponseLeavesDisputeArbitrating() {
        when(gateway.requestRuling(any(), any())).thenReturn(Optional.empty());
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verifyNoInteractions(settlement);
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.ARBITRATING);
    }

    @Test
    void applyRuling_recordsProposal_doesNotSettle() {
        DisputeModel arb = DisputeModel.open(disputedTask.id(), clientId, RejectReason.B_FACTUAL, "corr")
                .startArbitrating();
        when(disputeRepository.findById(arb.id())).thenReturn(Optional.of(arb));

        service.applyRuling(arb.id(), new RulingInfo(RulingCategory.NOT_FULFILLED, "off-topic"));

        // Ruling recorded, dispute now RULED, but NO settlement and NO task lock/resolve happened.
        ArgumentCaptor<DisputeModel> saved = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(DisputeStatus.RULED);
        verifyNoInteractions(settlement);
        verify(taskRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void applyRulingIsNoOpWhenDisputeAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        service.applyRuling(resolved.id(), new RulingInfo(RulingCategory.NOT_FULFILLED, "late"));

        verifyNoInteractions(settlement);
    }

    @Test
    void escalateTransitionsArbitratingToEscalated() {
        DisputeModel arbitrating = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating();
        when(disputeRepository.findById(arbitrating.id())).thenReturn(Optional.of(arbitrating));

        service.escalate(arbitrating.id());

        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.ESCALATED);
    }

    @Test
    void escalateIsNoOpWhenAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR,
                        Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        service.escalate(resolved.id());

        verify(disputeRepository, never()).save(any());
    }

    @Test
    void adminRuleOnEscalatedRefundsAndResolvesAtTierTwo() {
        DisputeModel escalated = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating().escalate();
        when(disputeRepository.findById(escalated.id())).thenReturn(Optional.of(escalated));

        service.adminRule(escalated.id(), RulingCategory.NOT_FULFILLED, "backstop refund", UUID.randomUUID());

        verify(settlement).settleRejected(eq(disputedTask.id()), eq(clientId), eq(Money.of("100.00")));
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        DisputeModel saved = cap.getValue();
        assertThat(saved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(saved.effectiveRuling().get().decidedBy()).isEqualTo(RulingDecidedBy.ADMINISTRATOR);
        assertThat(saved.effectiveRuling().get().tier()).isEqualTo(2);
    }

    @Test
    void adminRuleFulfilledPaysBuilder() {
        DisputeModel escalated = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating().escalate();
        when(disputeRepository.findById(escalated.id())).thenReturn(Optional.of(escalated));

        service.adminRule(escalated.id(), RulingCategory.FULFILLED, "meets spec", UUID.randomUUID());

        verify(settlement).settleAccepted(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
    }

    @Test
    void adminRuleRejectedWhenDisputeAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR,
                        Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.adminRule(resolved.id(), RulingCategory.NOT_FULFILLED, "late", UUID.randomUUID()))
                .isInstanceOf(com.hireai.utility.exception.DomainException.class);
        verifyNoInteractions(settlement);
    }

    @Test
    void staleArbitratingDisputeIdsDelegatesToRepository() {
        Instant cutoff = Instant.parse("2026-07-01T00:00:00Z");
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(disputeRepository.findStaleArbitratingIds(cutoff)).thenReturn(ids);

        assertThat(service.staleArbitratingDisputeIds(cutoff)).isEqualTo(ids);
    }

    /** OPEN dispute for {@code disputedTask}, raised by {@code clientId}, immediately proposed-ruled at tier 1. */
    private DisputeModel ruledDispute(RulingCategory category) {
        return DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "corr")
                .recordRuling(new Ruling(1, category, "r", RulingDecidedBy.ARBITRATOR, Instant.now()));
    }

    @Test
    void acceptRuling_settlesFromProposal() {
        DisputeModel ruled = ruledDispute(RulingCategory.FULFILLED);
        when(disputeRepository.findById(ruled.id())).thenReturn(Optional.of(ruled));

        service.acceptRuling(ruled.id(), clientId);

        verify(settlement).settleAccepted(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RESOLVED);
    }

    @Test
    void acceptRuling_byNonOwner_throws() {
        when(disputeRepository.findById(any())).thenReturn(Optional.of(ruledDispute(RulingCategory.FULFILLED)));

        assertThatThrownBy(() -> service.acceptRuling(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class).hasMessageContaining("not your dispute");
        verifyNoInteractions(settlement);
    }

    @Test
    void acceptRuling_whenNotRuled_throws() {
        DisputeModel arbitrating = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .startArbitrating();
        when(disputeRepository.findById(arbitrating.id())).thenReturn(Optional.of(arbitrating));

        assertThatThrownBy(() -> service.acceptRuling(arbitrating.id(), clientId))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(settlement);
    }

    @Test
    void appeal_movesToEscalated_noSettlement() {
        DisputeModel ruled = ruledDispute(RulingCategory.FULFILLED);
        when(disputeRepository.findById(ruled.id())).thenReturn(Optional.of(ruled));

        service.appeal(ruled.id(), clientId);

        ArgumentCaptor<DisputeModel> saved = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(DisputeStatus.ESCALATED);
        verifyNoInteractions(settlement);
    }

    @Test
    void appeal_byNonOwner_throws() {
        when(disputeRepository.findById(any())).thenReturn(Optional.of(ruledDispute(RulingCategory.FULFILLED)));

        assertThatThrownBy(() -> service.appeal(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class).hasMessageContaining("not your dispute");
        verifyNoInteractions(settlement);
    }
}
