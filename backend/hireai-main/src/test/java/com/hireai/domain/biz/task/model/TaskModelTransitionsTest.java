package com.hireai.domain.biz.task.model;

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
    void markAwaitingCapacityMovesSubmittedToAwaitingCapacity() {
        TaskModel awaiting = submitted().markAwaitingCapacity();
        assertThat(awaiting.status()).isEqualTo(TaskStatus.AWAITING_CAPACITY);
    }

    @Test
    void markAwaitingCapacityRejectsNonSubmittedSource() {
        TaskModel executing = submitted().assignAndQueue(UUID.randomUUID()).markExecuting();
        assertThatThrownBy(executing::markAwaitingCapacity)
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
    void markFailedFromQueuedOrExecuting() {
        TaskModel fromQueued = submitted().assignAndQueue(UUID.randomUUID()).markFailed();
        assertThat(fromQueued.status()).isEqualTo(TaskStatus.FAILED);

        TaskModel fromExecuting = submitted().assignAndQueue(UUID.randomUUID()).markExecuting().markFailed();
        assertThat(fromExecuting.status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void markFailedRejectsSubmittedSource() {
        assertThatThrownBy(() -> submitted().markFailed())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void markCancelledFromAwaitingCapacity() {
        TaskModel awaiting = submitted().markAwaitingCapacity();
        TaskModel cancelled = awaiting.markCancelled();

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void markCancelledRejectsOtherStatuses() {
        assertThatThrownBy(() -> submitted().markCancelled())
                .isInstanceOf(DomainException.class);
    }

    @Test
    void assignAndQueueAcceptsAwaitingCapacity() {
        UUID agentVersionId = UUID.randomUUID();
        TaskModel awaiting = submitted().markAwaitingCapacity();
        TaskModel queued = awaiting.assignAndQueue(agentVersionId);

        assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(queued.agentVersionId()).isEqualTo(agentVersionId);
    }

    @Test
    void markAwaitingCapacityIsIdempotentFromAwaitingCapacity() {
        TaskModel awaiting = submitted().markAwaitingCapacity();
        TaskModel again = awaiting.markAwaitingCapacity();

        assertThat(again.status()).isEqualTo(TaskStatus.AWAITING_CAPACITY);
        assertThat(again).isNotSameAs(awaiting);
    }
}
