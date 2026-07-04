package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.dispute.impl.DisputeReadAppServiceImpl;
import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisputeReadAppServiceImplTest {

    DisputeRepository disputeRepository;
    TaskRepository taskRepository;
    AgentRepository agentRepository;
    DisputeQueryPort disputeQueryPort;
    DisputeReadAppServiceImpl service;

    UUID clientId = UUID.randomUUID();
    UUID builderId = UUID.randomUUID();
    UUID agentVersionId = UUID.randomUUID();
    UUID strangerId = UUID.randomUUID();

    TaskModel task;
    DisputeModel dispute;

    @BeforeEach
    void setUp() {
        disputeRepository = mock(DisputeRepository.class);
        taskRepository = mock(TaskRepository.class);
        agentRepository = mock(AgentRepository.class);
        disputeQueryPort = mock(DisputeQueryPort.class);
        service = new DisputeReadAppServiceImpl(disputeRepository, taskRepository, agentRepository, disputeQueryPort);

        // Build a task owned by clientId with a known agentVersionId
        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        task = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "ok", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "mismatch");

        // Build a resolved dispute with one ruling
        Ruling ruling = new Ruling(1, RulingCategory.FULFILLED, "looks good",
                RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T10:00:00Z"));
        dispute = DisputeModel.open(task.id(), clientId, RejectReason.A_MISMATCH, "corr-1")
                .startArbitrating()
                .recordRuling(ruling)
                .resolve();

        when(taskRepository.findById(task.id())).thenReturn(Optional.of(task));
        when(agentRepository.findOwnerByVersionId(agentVersionId)).thenReturn(Optional.of(builderId));
        when(disputeRepository.findByTaskId(task.id())).thenReturn(Optional.of(dispute));
    }

    @Test
    void clientCanReadDisputeOutcome() {
        DisputeModel result = service.getOutcomeForUser(task.id(), clientId);
        assertThat(result).isEqualTo(dispute);
    }

    @Test
    void owningBuilderCanReadDisputeOutcome() {
        DisputeModel result = service.getOutcomeForUser(task.id(), builderId);
        assertThat(result).isEqualTo(dispute);
    }

    @Test
    void strangerGetsNotFound() {
        assertThatThrownBy(() -> service.getOutcomeForUser(task.id(), strangerId))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void taskWithNoDisputeGetsNotFound() {
        when(disputeRepository.findByTaskId(task.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOutcomeForUser(task.id(), clientId))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void unknownTaskGetsNotFound() {
        UUID unknownTaskId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getOutcomeForUser(unknownTaskId, clientId))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void clientCanReadDisputeOutcomeByDisputeId() {
        when(disputeRepository.findById(dispute.id())).thenReturn(Optional.of(dispute));

        DisputeModel result = service.getOutcomeByDispute(dispute.id(), clientId);

        assertThat(result).isEqualTo(dispute);
    }

    @Test
    void owningBuilderCanReadDisputeOutcomeByDisputeId() {
        when(disputeRepository.findById(dispute.id())).thenReturn(Optional.of(dispute));

        DisputeModel result = service.getOutcomeByDispute(dispute.id(), builderId);

        assertThat(result).isEqualTo(dispute);
    }

    @Test
    void strangerGetsNotFoundByDisputeId() {
        when(disputeRepository.findById(dispute.id())).thenReturn(Optional.of(dispute));

        assertThatThrownBy(() -> service.getOutcomeByDispute(dispute.id(), strangerId))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void unknownDisputeIdGetsNotFound() {
        UUID unknownDisputeId = UUID.randomUUID();
        when(disputeRepository.findById(unknownDisputeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOutcomeByDispute(unknownDisputeId, clientId))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));
    }

    @Test
    void myDisputesDelegatesToDisputeQueryPort() {
        List<DisputeMineRow> rows = List.of(new DisputeMineRow(
                UUID.randomUUID(), task.id(), "t", "RULED", "FULFILLED", Instant.now()));
        when(disputeQueryPort.findDisputesForClient(clientId)).thenReturn(rows);

        List<DisputeMineRow> result = service.myDisputes(clientId);

        assertThat(result).isEqualTo(rows);
    }
}
