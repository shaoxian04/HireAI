package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.TaskSettlementRecord;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TaskModel2DTOConverter: the amounts shown are the recorded settlement's.
 */
class TaskModel2DTOConverterTest {

    private static final UUID AGENT_VERSION_ID = UUID.randomUUID();

    /** Build a RESOLVED task by driving through the domain state machine. */
    private TaskModel resolvedWith(Money budget, TaskResolution resolution) {
        TaskModel base = TaskModel.submit(UUID.randomUUID(), "title", "desc",
                budget, new OutputSpec(OutputFormat.TEXT, null, null), "cat");
        TaskModel disputed = base
                .assignAndQueue(AGENT_VERSION_ID)
                .markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "{}", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "mismatch");
        return disputed.resolveDispute(resolution);
    }

    private static TaskSettlementRecord recorded(TaskModel task, SettlementModel whole) {
        return new TaskSettlementRecord(task.budget(), Optional.of(whole), List.of());
    }

    @Test
    void anUnsettledTaskShowsNoAmounts() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.ACCEPTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task, new TaskSettlementRecord(task.budget(), Optional.empty(),
                List.of()));
        assertThat(dto.payoutAmount()).isNull();
        assertThat(dto.commissionAmount()).isNull();
        assertThat(dto.refundAmount()).isNull();
    }

    @Test
    void multiItemAmountsAreTheItemsRecordedSettlements() {
        TaskModel task = resolvedWith(Money.of("0.30"), TaskResolution.ACCEPTED);
        // Three 0.10 Items, their commissions allocated on the running total: 0.02 + 0.01 + 0.02 = 0.05 (15%).
        TaskSettlementRecord record = new TaskSettlementRecord(task.budget(), Optional.empty(), List.of(
                ItemSettlementModel.accepted(task.id(), UUID.randomUUID(), 1, Money.of("0.08"), Money.of("0.02")),
                ItemSettlementModel.accepted(task.id(), UUID.randomUUID(), 2, Money.of("0.09"), Money.of("0.01")),
                ItemSettlementModel.accepted(task.id(), UUID.randomUUID(), 3, Money.of("0.08"), Money.of("0.02"))));
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task, record);
        assertThat(dto.payoutAmount()).isEqualByComparingTo("0.25");
        assertThat(dto.commissionAmount()).isEqualByComparingTo("0.05");
        assertThat(dto.refundAmount()).isNull();
    }

    @Test
    void acceptedResolutionShowsFullPayout() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.ACCEPTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task, recorded(task,
                SettlementModel.accepted(task.id(), Money.of("85.00"), Money.of("15.00"))));

        assertThat(dto.resolution()).isEqualTo("ACCEPTED");
        assertThat(dto.payoutAmount()).isEqualByComparingTo(new BigDecimal("85.00"));
        assertThat(dto.commissionAmount()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(dto.refundAmount()).isNull();
    }

    @Test
    void partiallyAcceptedResolutionShowsSplitAmounts() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.PARTIALLY_ACCEPTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task, recorded(task,
                SettlementModel.split(task.id(), Money.of("42.50"), Money.of("7.50"))));

        assertThat(dto.resolution()).isEqualTo("PARTIALLY_ACCEPTED");
        // payout=42.50, commission=7.50, refund=50.00; payout + commission + refund == budget
        assertThat(dto.payoutAmount()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(dto.commissionAmount()).isEqualByComparingTo(new BigDecimal("7.50"));
        assertThat(dto.refundAmount()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(dto.payoutAmount().add(dto.commissionAmount()).add(dto.refundAmount()))
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void rejectedResolutionShowsFullRefund() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.REJECTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task, recorded(task, SettlementModel.rejected(task.id())));

        assertThat(dto.resolution()).isEqualTo("REJECTED");
        assertThat(dto.refundAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(dto.payoutAmount()).isNull();
        assertThat(dto.commissionAmount()).isNull();
    }
}
