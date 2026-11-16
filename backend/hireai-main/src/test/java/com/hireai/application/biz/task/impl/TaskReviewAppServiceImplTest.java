package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
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
    @Mock DisputeAppService disputeAppService;
    @Mock ReputationWriteAppService reputationWriteAppService;
    @Mock com.hireai.domain.biz.task.rejection.repository.RejectionRepository rejectionRepository;
    @Mock org.springframework.context.ApplicationEventPublisher eventPublisher;

    TaskReviewAppServiceImpl service;

    final UUID clientId = UUID.randomUUID();
    final UUID builderId = UUID.randomUUID();
    final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TaskReviewAppServiceImpl(taskRepository, agentRepository, settlementWriteAppService,
                new AcceptedSettlement(taskRepository, agentRepository, settlementWriteAppService,
                        reputationWriteAppService),
                rejectionRepository,
                new com.hireai.domain.biz.task.rejection.model.RejectionWindows(
                        java.time.Duration.ofHours(2), java.time.Duration.ofHours(24)),
                eventPublisher, java.time.Clock.systemUTC(),
                org.mockito.Mockito.mock(com.hireai.application.biz.task.item.TaskItemAppService.class),
                org.mockito.Mockito.mock(com.hireai.application.biz.acceptance.aireview.AiReviewAppService.class));
    }

    /** Build a task in PENDING_REVIEW (passed the validation gate) so accept/reject are legal. */
    private TaskModel pendingReviewTask() {
        TaskModel t = TaskModel.submit(clientId, "t", "d", Money.of("20.00"),
                        new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(versionId).markExecuting();
        TaskModel received = t.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), t.id(), "COMPLETED", "{}", null, Instant.now()));
        return received.passValidation();
    }

    @Test
    void acceptSettlesAndSavesTask() {
        TaskModel task = pendingReviewTask();
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

    /**
     * A_MISMATCH reject (#95): the task waits for the Agent's response and its notice is announced.
     * Nothing is arbitrated and no money moves yet.
     */
    @Test
    void mismatchRejectWaitsForTheAgentAndNeverTouchesSettlementOrDisputes() {
        TaskModel task = pendingReviewTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(task.id(), clientId, RejectReason.A_MISMATCH, "not good");

        verify(disputeAppService, never()).openDispute(any(), any(), any());
        verify(settlementWriteAppService, never()).settleRejected(any(), any(), any());
        verify(rejectionRepository).save(any());
        verify(eventPublisher).publishEvent(any(Object.class));
        ArgumentCaptor<TaskModel> saved = ArgumentCaptor.forClass(TaskModel.class);
        verify(taskRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TaskStatus.AWAITING_AGENT_RESPONSE);
    }

    @Test
    void nonOwnerGetsNotFound() {
        TaskModel task = pendingReviewTask();
        when(taskRepository.findByIdForUpdate(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.accept(task.id(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }
}
