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

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TaskModel2DTOConverter — verifies settlement display amounts per resolution.
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

    @Test
    void acceptedResolutionShowsFullPayout() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.ACCEPTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task);

        assertThat(dto.resolution()).isEqualTo("ACCEPTED");
        assertThat(dto.payoutAmount()).isEqualByComparingTo(new BigDecimal("85.00"));
        assertThat(dto.commissionAmount()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(dto.refundAmount()).isNull();
    }

    @Test
    void partiallyAcceptedResolutionShowsSplitAmounts() {
        TaskModel task = resolvedWith(Money.of("100.00"), TaskResolution.PARTIALLY_ACCEPTED);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task);

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
        TaskDTO dto = TaskModel2DTOConverter.toDTO(task);

        assertThat(dto.resolution()).isEqualTo("REJECTED");
        assertThat(dto.refundAmount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(dto.payoutAmount()).isNull();
        assertThat(dto.commissionAmount()).isNull();
    }
}
