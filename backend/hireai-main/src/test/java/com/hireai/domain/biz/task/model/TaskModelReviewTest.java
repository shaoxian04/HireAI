package com.hireai.domain.biz.task.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client review transitions: accept/reject from PENDING_REVIEW (the validation gate has passed). */
class TaskModelReviewTest {

    /** Build a task in PENDING_REVIEW via the full happy-path transition chain. */
    private TaskModel pendingReviewTask() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc",
                Money.of("20.00"), new OutputSpec(OutputFormat.TEXT, null, "short"), "summarisation");
        task = task.assignAndQueue(UUID.randomUUID()).markExecuting();
        TaskModel received = task.recordResult(TaskResultModel.rehydrate(
                UUID.randomUUID(), task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null, Instant.now()));
        return received.passValidation();
    }

    @Test
    void acceptResolvesTheTask() {
        TaskModel resolved = pendingReviewTask().accept();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.ACCEPTED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.rejectionReason()).isNull();
    }

    @Test
    void rejectResolvesWithTrimmedReason() {
        TaskModel resolved = pendingReviewTask().reject("  not what I asked for  ");
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
        assertThat(resolved.rejectionReason()).isEqualTo("not what I asked for");
    }

    @Test
    void rejectWithoutReasonStoresNull() {
        assertThat(pendingReviewTask().reject(null).rejectionReason()).isNull();
        assertThat(pendingReviewTask().reject("   ").rejectionReason()).isNull();
    }

    @Test
    void rejectReasonAtExactly500CharsIsAccepted() {
        String exactly500 = "x".repeat(500);
        TaskModel resolved = pendingReviewTask().reject(exactly500);
        assertThat(resolved.rejectionReason()).isEqualTo(exactly500);
    }

    @Test
    void rejectReasonOver500CharsAfterTrimmingIsRejected() {
        assertThatThrownBy(() -> pendingReviewTask().reject("  " + "x".repeat(501) + "  "))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void rejectReasonOver500CharsIsRejected() {
        assertThatThrownBy(() -> pendingReviewTask().reject("x".repeat(501)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));
    }

    @Test
    void acceptFromNonResultReceivedThrows() {
        TaskModel executing = TaskModel.submit(UUID.randomUUID(), "t", "d",
                        Money.of("5.00"), new OutputSpec(OutputFormat.TEXT, null, null), "c")
                .assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(executing::accept)
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).resultCode())
                        .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION));
    }

    @Test
    void resolvedTaskCannotBeResolvedAgain() {
        TaskModel resolved = pendingReviewTask().accept();
        assertThatThrownBy(resolved::accept).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> resolved.reject("again")).isInstanceOf(DomainException.class);
    }
}
