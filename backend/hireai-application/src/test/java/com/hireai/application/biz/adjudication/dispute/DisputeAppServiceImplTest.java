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
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
    void openWithSynchronousNotFulfilledRulingRefundsAndResolves() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.NOT_FULFILLED, "no")));

        UUID disputeId = service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);

        verify(settlement).settleRejected(eq(disputedTask.id()), eq(clientId), eq(Money.of("100.00")));
        ArgumentCaptor<DisputeModel> cap = ArgumentCaptor.forClass(DisputeModel.class);
        verify(disputeRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(disputeId).isNotNull();
        // task resolved
        ArgumentCaptor<TaskModel> tcap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository, atLeastOnce()).save(tcap.capture());
        assertThat(tcap.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
    }

    @Test
    void synchronousPartialRulingSplits() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.PARTIALLY_FULFILLED, "half")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verify(settlement).settleSplit(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
        // task must be labelled PARTIALLY_ACCEPTED, not ACCEPTED
        ArgumentCaptor<TaskModel> tcap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository, atLeastOnce()).save(tcap.capture());
        assertThat(tcap.getValue().resolution()).isEqualTo(TaskResolution.PARTIALLY_ACCEPTED);
    }

    @Test
    void synchronousFulfilledRulingPaysBuilder() {
        when(gateway.requestRuling(any(), any()))
                .thenReturn(Optional.of(new RulingInfo(RulingCategory.FULFILLED, "ok")));
        service.openDispute(disputedTask, clientId, RejectReason.A_MISMATCH);
        verify(settlement).settleAccepted(eq(disputedTask.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
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
    void applyRulingIsNoOpWhenDisputeAlreadyResolved() {
        DisputeModel resolved = DisputeModel.open(disputedTask.id(), clientId, RejectReason.A_MISMATCH, "c")
                .recordRuling(new Ruling(1, RulingCategory.FULFILLED, "x", RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T00:00:00Z")))
                .resolve();
        when(disputeRepository.findById(resolved.id())).thenReturn(Optional.of(resolved));

        service.applyRuling(resolved.id(), new RulingInfo(RulingCategory.NOT_FULFILLED, "late"));

        verifyNoInteractions(settlement);
    }
}
