package com.hireai.application.biz.task;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.impl.TaskReviewAppServiceImpl;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskReviewRejectGateTest {

    TaskRepository taskRepository;
    AgentRepository agentRepository;
    SettlementWriteAppService settlement;
    DisputeAppService disputeAppService;
    TaskReviewAppServiceImpl service;

    UUID clientId = UUID.randomUUID();
    UUID builderId = UUID.randomUUID();
    UUID agentVersionId = UUID.randomUUID();
    TaskModel pendingReview;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        agentRepository = mock(AgentRepository.class);
        settlement = mock(SettlementWriteAppService.class);
        disputeAppService = mock(DisputeAppService.class);
        service = new TaskReviewAppServiceImpl(taskRepository, agentRepository, settlement, disputeAppService);

        when(taskRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(agentRepository.findOwnerByVersionId(agentVersionId)).thenReturn(Optional.of(builderId));

        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        pendingReview = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "ok", null))
                .passValidation();
        when(taskRepository.findByIdForUpdate(pendingReview.id())).thenReturn(Optional.of(pendingReview));
    }

    @Test
    void changedMindChargesClientAndOpensNoDispute() {
        service.reject(pendingReview.id(), clientId, RejectReason.D_CHANGED_MIND, "not needed");
        verify(settlement).settleAccepted(eq(pendingReview.id()), eq(clientId), eq(builderId), eq(Money.of("100.00")));
        verifyNoInteractions(disputeAppService);
        ArgumentCaptor<TaskModel> cap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(cap.getValue().rejectReasonCategory()).isEqualTo(RejectReason.D_CHANGED_MIND);
    }

    @Test
    void mismatchOpensDispute() {
        service.reject(pendingReview.id(), clientId, RejectReason.A_MISMATCH, "wrong");
        verify(disputeAppService).openDispute(any(TaskModel.class), eq(clientId), eq(RejectReason.A_MISMATCH));
        verify(settlement, never()).settleRejected(any(), any(), any());
        ArgumentCaptor<TaskModel> cap = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(cap.capture());
        assertThat(cap.getValue().status()).isEqualTo(TaskStatus.DISPUTED);
    }

    @Test
    void nullReasonCategoryRejected() {
        assertThatThrownBy(() -> service.reject(pendingReview.id(), clientId, null, "x"))
                .isInstanceOf(DomainException.class);
    }
}
