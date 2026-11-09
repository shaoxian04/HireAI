package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.FailureReason;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelTransitionsTest {

    private TaskModel submitted() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    @Test
    void assignAndQueueMovesSubmittedToQueuedAndSetsAgentVersion() {
        UUID agentVersionId = UUID.randomUUID();
        TaskModel queued = submitted().assignAndQueue(agentVersionId);

        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void assignAndQueueReturnsNewInstanceLeavingOriginalUnchanged() {
        TaskModel original = submitted();
        TaskModel queued = original.assignAndQueue(UUID.randomUUID());

        assertThat(queued).isNotSameAs(original);
        assertThat(original.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(original.agentVersionId()).isNull();
    }

    @Test
    void assignAndQueueRejectsNullAgentVersion() {
        assertThatThrownBy(() -> submitted().assignAndQueue(null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void assignAndQueueRejectsNonSubmittedSource() {
        TaskModel queued = submitted().assignAndQueue(UUID.randomUUID());
        assertThatThrownBy(() -> queued.assignAndQueue(UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markExecutingMovesQueuedToExecuting() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThat(executing.status()).isEqualTo(TaskStatus.EXECUTING);
    }

    @Test
    void markExecutingRejectsNonQueuedSource() {
        assertThatThrownBy(() -> submitted().markExecuting())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordResultMovesExecutingToResultReceivedAndAttachesResult() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        TaskResultModel result = TaskResultModel.record(executing.id(), "COMPLETED", "{}", null);

        TaskModel received = executing.recordResult(result);

        assertThat(received.status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(received.result()).isSameAs(result);
    }

    @Test
    void recordResultAcceptsQueuedSourceForAnEarlyCallback() {
        // #63: an Agent may call back before the platform has marked the task EXECUTING.
        TaskModel queued = submitted().assignAndQueue(UUID.randomUUID());
        TaskResultModel result = TaskResultModel.record(queued.id(), "COMPLETED", "{}", null);

        TaskModel received = queued.recordResult(result);

        assertThat(received.status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(received.result()).isSameAs(result);
    }

    @Test
    void recordResultRejectsAwaitingAgentAndTerminalSources() {
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null);
        TaskModel awaiting = submitted().markAwaitingAgent();
        TaskModel failed = submitted().assignAndQueue(UUID.randomUUID()).markFailed(FailureReason.AGENT_UNREACHABLE);
        TaskModel timedOut = submitted().assignAndQueue(UUID.randomUUID()).markTimedOut();

        assertThatThrownBy(() -> awaiting.recordResult(result)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> failed.recordResult(result)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> timedOut.recordResult(result)).isInstanceOf(DomainException.class);
    }

    @Test
    void markExecutingIsANoOpFromAnyLaterState() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        TaskModel received = submitted().assignAndQueue(UUID.randomUUID())
                .recordResult(TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null));
        TaskModel failed = submitted().assignAndQueue(UUID.randomUUID()).markFailed(FailureReason.AGENT_UNREACHABLE);
        TaskModel timedOut = submitted().assignAndQueue(UUID.randomUUID()).markTimedOut();
        TaskModel pendingReview = received.passValidation();

        assertThat(executing.markExecuting().status()).isEqualTo(TaskStatus.EXECUTING);
        assertThat(received.markExecuting().status()).isEqualTo(TaskStatus.RESULT_RECEIVED);
        assertThat(failed.markExecuting().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(timedOut.markExecuting().status()).isEqualTo(TaskStatus.TIMED_OUT);
        assertThat(pendingReview.markExecuting().status()).isEqualTo(TaskStatus.PENDING_REVIEW);
    }

    @Test
    void markExecutingRejectsAwaitingAgentSource() {
        TaskModel awaiting = submitted().markAwaitingAgent();
        assertThatThrownBy(awaiting::markExecuting).isInstanceOf(DomainException.class);
    }

    @Test
    void recordResultRejectsNonExecutingSource() {
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "COMPLETED", "{}", null);
        assertThatThrownBy(() -> submitted().recordResult(result))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void recordResultRejectsNullResult() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(() -> executing.recordResult(null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markAwaitingAgentMovesSubmittedToAwaitingAgent() {
        TaskModel awaiting = submitted().markAwaitingAgent();
        assertThat(awaiting.status()).isEqualTo(TaskStatus.AWAITING_AGENT);
    }

    @Test
    void markAwaitingAgentRejectsNonSubmittedSource() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(executing::markAwaitingAgent)
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markTimedOutFromQueuedOrExecuting() {
        TaskModel fromQueued = submitted().assignAndQueue(UUID.randomUUID()).markTimedOut();
        assertThat(fromQueued.status()).isEqualTo(TaskStatus.TIMED_OUT);

        TaskModel fromExecuting = submitted().assignAndQueue(UUID.randomUUID()).markExecuting().markTimedOut();
        assertThat(fromExecuting.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test
    void markTimedOutRejectsSubmittedSource() {
        assertThatThrownBy(() -> submitted().markTimedOut())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aTimeoutIsTheAgentsFaultOnlyOnceItIsExecuting() {
        TaskModel queued = submitted().assignAndQueue(UUID.randomUUID());
        assertThat(queued.timeoutIsAgentFault()).isFalse();
        assertThat(queued.markExecuting().timeoutIsAgentFault()).isTrue();
    }

    @Test
    void markFailedFromQueuedOrExecuting() {
        TaskModel fromQueued = submitted().assignAndQueue(UUID.randomUUID()).markFailed(FailureReason.AGENT_UNREACHABLE);
        assertThat(fromQueued.status()).isEqualTo(TaskStatus.FAILED);

        TaskModel fromExecuting = submitted().assignAndQueue(UUID.randomUUID()).markExecuting().markFailed(FailureReason.AGENT_UNREACHABLE);
        assertThat(fromExecuting.status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void markFailedRejectsSubmittedSource() {
        assertThatThrownBy(() -> submitted().markFailed(FailureReason.AGENT_UNREACHABLE))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markCancelledFromAwaitingAgent() {
        TaskModel awaiting = submitted().markAwaitingAgent();
        TaskModel cancelled = awaiting.markCancelled(FailureReason.NO_ELIGIBLE_AGENT);

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void markCancelledFromSubmitted() {
        TaskModel cancelled = submitted().markCancelled(FailureReason.NO_ELIGIBLE_AGENT);

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void markCancelledRejectsOtherStatuses() {
        assertThatThrownBy(() -> submitted().assignAndQueue(UUID.randomUUID()).markCancelled(FailureReason.NO_ELIGIBLE_AGENT))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void assignAndQueueAcceptsAwaitingAgent() {
        UUID agentVersionId = UUID.randomUUID();
        TaskModel awaiting = submitted().markAwaitingAgent();
        TaskModel queued = awaiting.assignAndQueue(agentVersionId);

        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void markAwaitingAgentIsIdempotentFromAwaitingAgent() {
        TaskModel awaiting = submitted().markAwaitingAgent();
        TaskModel again = awaiting.markAwaitingAgent();

        assertThat(again.status()).isEqualTo(TaskStatus.AWAITING_AGENT);
        assertThat(again).isNotSameAs(awaiting);
    }
}
