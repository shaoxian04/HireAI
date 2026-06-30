package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelValidationTransitionsTest {

    /** Build a task in RESULT_RECEIVED via the real transition chain. */
    private TaskModel resultReceivedTask() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "title", "desc",
                Money.of("20.00"), new OutputSpec(OutputFormat.TEXT, null, "short"), "summarisation");
        task = task.assignAndQueue(UUID.randomUUID()).markExecuting();
        return task.recordResult(TaskResultModel.record(task.id(), "COMPLETED", "{\"summary\":\"ok\"}", null));
    }

    @Test
    void passValidationGoesToPendingReview() {
        TaskModel t = resultReceivedTask().passValidation();
        assertThat(t.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
    }

    @Test
    void failValidationGoesToSpecViolation() {
        TaskModel t = resultReceivedTask().failValidation();
        assertThat(t.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
    }

    @Test
    void passValidationPreservesResultAndAgentVersion() {
        TaskModel received = resultReceivedTask();
        TaskModel pending = received.passValidation();
        assertThat(pending.result()).isSameAs(received.result());
        assertThat(pending.agentVersionId()).isEqualTo(received.agentVersionId());
        assertThat(pending.id()).isEqualTo(received.id());
    }

    @Test
    void failValidationPreservesResultAndAgentVersion() {
        TaskModel received = resultReceivedTask();
        TaskModel violated = received.failValidation();
        assertThat(violated.result()).isSameAs(received.result());
        assertThat(violated.agentVersionId()).isEqualTo(received.agentVersionId());
    }

    @Test
    void passValidationRejectsNonResultReceived() {
        TaskModel submitted = TaskModel.submit(UUID.randomUUID(), "t", "d",
                Money.of("5.00"), new OutputSpec(OutputFormat.TEXT, null, null), "c");
        assertThatThrownBy(submitted::passValidation).isInstanceOf(DomainException.class);
    }

    @Test
    void failValidationRejectsNonResultReceived() {
        TaskModel submitted = TaskModel.submit(UUID.randomUUID(), "t", "d",
                Money.of("5.00"), new OutputSpec(OutputFormat.TEXT, null, null), "c");
        assertThatThrownBy(submitted::failValidation).isInstanceOf(DomainException.class);
    }

    @Test
    void acceptRequiresPendingReview() {
        TaskModel received = resultReceivedTask();
        // accept from RESULT_RECEIVED must now throw
        assertThatThrownBy(received::accept).isInstanceOf(DomainException.class);
        // accept from PENDING_REVIEW succeeds
        TaskModel resolved = received.passValidation().accept();
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.ACCEPTED);
    }

    @Test
    void rejectRequiresPendingReview() {
        TaskModel received = resultReceivedTask();
        // reject from RESULT_RECEIVED must now throw
        assertThatThrownBy(() -> received.reject("bad")).isInstanceOf(DomainException.class);
        // reject from PENDING_REVIEW succeeds
        TaskModel resolved = received.passValidation().reject("bad");
        assertThat(resolved.status()).isEqualTo(TaskStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(TaskResolution.REJECTED);
    }
}
