package com.hireai.domain.biz.task.model;

import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskResultModelTest {

    @Test
    void recordBuildsResultWithGeneratedIdAndTimestamp() {
        UUID taskId = UUID.randomUUID();
        TaskResultModel result = TaskResultModel.record(taskId, "COMPLETED", "{\"k\":\"v\"}", "https://x/y");

        assertThat(result.id()).isNotNull();
        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.agentStatus()).isEqualTo("COMPLETED");
        assertThat(result.resultPayloadJson()).isEqualTo("{\"k\":\"v\"}");
        assertThat(result.resultUrl()).isEqualTo("https://x/y");
        assertThat(result.receivedAt()).isNotNull();
    }

    @Test
    void allowsNullResultUrl() {
        TaskResultModel result = TaskResultModel.record(UUID.randomUUID(), "FAILED", "{}", null);
        assertThat(result.resultUrl()).isNull();
    }

    @Test
    void rejectsBlankAgentStatus() {
        assertThatThrownBy(() -> TaskResultModel.record(UUID.randomUUID(), "  ", "{}", null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullPayload() {
        assertThatThrownBy(() -> TaskResultModel.record(UUID.randomUUID(), "COMPLETED", null, null))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rehydrateRebuildsFromPersistedState() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant at = Instant.now();
        TaskResultModel result = TaskResultModel.rehydrate(id, taskId, "COMPLETED", "{}", null, at);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.receivedAt()).isEqualTo(at);
    }
}
