package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Routed failover (#78): {@code reassign} swaps the Agent of a task that never started, and nothing else. */
class TaskModelReassignTest {

    private static final UUID FIRST = UUID.randomUUID();

    private TaskModel submitted() {
        return TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("10.00"),
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general");
    }

    private TaskModel queued() {
        return submitted().assignAndQueue(FIRST);
    }

    @Test
    void reassignKeepsTheTaskQueuedAndNamesTheNewVersion() {
        UUID next = UUID.randomUUID();

        TaskModel reassigned = queued().reassign(next);

        assertThat(reassigned.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(reassigned.agentVersionId()).isEqualTo(next);
        assertThat(reassigned.failureReason()).isNull();
    }

    @Test
    void reassignLeavesTheOriginalUntouched() {
        TaskModel original = queued();

        original.reassign(UUID.randomUUID());

        assertThat(original.agentVersionId()).isEqualTo(FIRST);
    }

    @Test
    void reassignKeepsEverythingElseOfTheTask() {
        TaskModel original = queued();

        TaskModel reassigned = original.reassign(UUID.randomUUID());

        assertThat(reassigned.id()).isEqualTo(original.id());
        assertThat(reassigned.clientId()).isEqualTo(original.clientId());
        assertThat(reassigned.budget()).isEqualTo(original.budget());
        assertThat(reassigned.category()).isEqualTo(original.category());
        assertThat(reassigned.outputSpec()).isEqualTo(original.outputSpec());
    }

    @Test
    void reassignRequiresANewVersion() {
        assertThatThrownBy(() -> queued().reassign(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void reassignToTheSameVersionIsRefused() {
        assertThatThrownBy(() -> queued().reassign(FIRST)).isInstanceOf(DomainException.class);
    }

    @Test
    void anExecutingTaskIsNeverReassigned() {
        // Failover covers only failures before the Agent started.
        TaskModel executing = queued().markExecuting();

        assertThatThrownBy(() -> executing.reassign(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }

    @Test
    void aTaskWithoutAnAgentIsNotReassigned() {
        assertThatThrownBy(() -> submitted().reassign(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }

    @Test
    void anEndedTaskIsNeverReassigned() {
        TaskModel failed = queued().markFailed(FailureReason.AGENT_BUSY);

        assertThatThrownBy(() -> failed.reassign(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }
}
