// TaskModelDisputeTest.java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelDisputeTest {

    private TaskModel pendingReview() {
        TaskModel submitted = TaskModel.submit(UUID.randomUUID(), "t", "d", Money.of("10.00"),
                new OutputSpec(com.hireai.domain.biz.task.enums.OutputFormat.TEXT, null, null), "cat");
        return submitted
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .recordResult(TaskResultModel.record(submitted.id(), "COMPLETED", "ok", null))
                .passValidation();
    }

    @Test
    void disputeMovesToDisputedAndRecordsCategory() {
        TaskModel d = pendingReview().dispute(RejectReason.A_MISMATCH, "  does not match  ");
        assertThat(d.status()).isEqualTo(TaskStatus.DISPUTED);
        assertThat(d.rejectReasonCategory()).isEqualTo(RejectReason.A_MISMATCH);
        assertThat(d.rejectionReason()).isEqualTo("does not match"); // trimmed
        assertThat(d.resolution()).isNull();
    }

    @Test
    void disputeRejectsChangedMindReason() {
        assertThatThrownBy(() -> pendingReview().dispute(RejectReason.D_CHANGED_MIND, "x"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void disputeRejectsFromNonPendingReview() {
        TaskModel disputed = pendingReview().dispute(RejectReason.A_MISMATCH, "x");
        assertThatThrownBy(() -> disputed.dispute(RejectReason.B_FACTUAL, "y"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void resolveDisputeMovesToResolvedWithResolution() {
        TaskModel resolved = pendingReview().dispute(RejectReason.A_MISMATCH, "x")
                .resolveDispute(TaskResolution.REJECTED);
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.rejectReasonCategory()).isEqualTo(RejectReason.A_MISMATCH); // preserved
    }

    @Test
    void chargeChangedMindResolvesRejectedWithDCategory() {
        TaskModel charged = pendingReview().chargeChangedMind("not needed anymore");
        assertThat(charged.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(charged.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(charged.rejectReasonCategory()).isEqualTo(RejectReason.D_CHANGED_MIND);
    }

    @Test
    void disputedCountsAsPendingEscrow() {
        assertThat(TaskStatus.DISPUTED.isPendingEscrow()).isTrue();
    }
}
