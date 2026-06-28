package com.hireai.domain.biz.task.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test for the pending-escrow classification (used by builder earnings). */
class TaskStatusTest {

    @Test
    void pendingEscrowCoversInFlightRoutedStatuses() {
        assertThat(TaskStatus.QUEUED.isPendingEscrow()).isTrue();
        assertThat(TaskStatus.EXECUTING.isPendingEscrow()).isTrue();
        assertThat(TaskStatus.RESULT_RECEIVED.isPendingEscrow()).isTrue();
        assertThat(TaskStatus.PENDING_REVIEW.isPendingEscrow()).isTrue();
        assertThat(TaskStatus.AWAITING_CAPACITY.isPendingEscrow()).isTrue();
    }

    @Test
    void pendingEscrowExcludesPreRouteAndTerminalStatuses() {
        assertThat(TaskStatus.SUBMITTED.isPendingEscrow()).isFalse();
        assertThat(TaskStatus.RESOLVED.isPendingEscrow()).isFalse();
        assertThat(TaskStatus.CANCELLED.isPendingEscrow()).isFalse();
        assertThat(TaskStatus.FAILED.isPendingEscrow()).isFalse();
        assertThat(TaskStatus.TIMED_OUT.isPendingEscrow()).isFalse();
        assertThat(TaskStatus.SPEC_VIOLATION.isPendingEscrow()).isFalse();
    }
}
