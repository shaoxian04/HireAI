package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every terminal failure records exactly one FailureReason (#74). */
class TaskModelFailureReasonTest {

    private TaskModel submitted() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    private TaskModel queued() {
        return submitted().assignAndQueue(UUID.randomUUID());
    }

    @Test
    void aLiveTaskHasNoFailureReason() {
        assertThat(submitted().failureReason()).isNull();
        assertThat(queued().markExecuting().failureReason()).isNull();
    }

    @Test
    void markFailedRecordsTheGivenReason() {
        TaskModel failed = queued().markFailed(FailureReason.AGENT_UNREACHABLE);

        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo(FailureReason.AGENT_UNREACHABLE);
    }

    @Test
    void markFailedRequiresAReason() {
        assertThatThrownBy(() -> queued().markFailed(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void aTimeoutWhileExecutingIsAnExecutionTimeout() {
        TaskModel timedOut = queued().markExecuting().markTimedOut();

        assertThat(timedOut.failureReason()).isEqualTo(FailureReason.EXECUTION_TIMEOUT);
    }

    @Test
    void aTimeoutWhileStillQueuedMeansDispatchWasAbandoned() {
        TaskModel timedOut = queued().markTimedOut();

        assertThat(timedOut.failureReason()).isEqualTo(FailureReason.DISPATCH_ABANDONED);
    }

    @Test
    void markCancelledRecordsTheGivenReason() {
        TaskModel cancelled = submitted().markAwaitingAgent().markCancelled(FailureReason.NO_ELIGIBLE_AGENT);

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.failureReason()).isEqualTo(FailureReason.NO_ELIGIBLE_AGENT);
    }

    @Test
    void failingValidationRecordsSpecViolation() {
        TaskModel received = queued().markExecuting()
                .recordResult(TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null));

        assertThat(received.failValidation().failureReason()).isEqualTo(FailureReason.SPEC_VIOLATION);
    }

    @Test
    void aSuccessfulPathNeverRecordsAReason() {
        TaskModel resolved = queued().markExecuting()
                .recordResult(TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null))
                .passValidation()
                .accept();

        assertThat(resolved.failureReason()).isNull();
    }

    @Test
    void theReasonCanBeSetOnlyOnce() {
        // A row that already carries a reason (e.g. rehydrated) can never be given a second one.
        TaskModel alreadyReasoned = new TaskModel(UUID.randomUUID(), UUID.randomUUID(), "t", "d",
                Money.of("10.00"), new OutputSpec(OutputFormat.TEXT, null, null), "general",
                TaskStatus.QUEUED, UUID.randomUUID(), null, Instant.now(),
                null, null, null, null, FailureReason.AGENT_BUSY);

        assertThatThrownBy(() -> alreadyReasoned.markFailed(FailureReason.AGENT_UNREACHABLE))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("already");
        assertThatThrownBy(alreadyReasoned::markTimedOut)
                .isInstanceOf(DomainException.class);
    }

    @Test
    void theReasonSurvivesLaterNoOpTransitions() {
        TaskModel failed = queued().markFailed(FailureReason.AGENT_BUSY);

        assertThat(failed.markExecuting().failureReason()).isEqualTo(FailureReason.AGENT_BUSY);
    }
}
