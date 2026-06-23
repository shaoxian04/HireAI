package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the hand-written TaskResultModel -> TaskResultDTO mapping (incl. nullable resultUrl). */
class TaskResult2DTOConverterTest {

    @Test
    void mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-06-06T10:15:30Z");
        TaskResultModel model = TaskResultModel.rehydrate(
                id, taskId, "COMPLETED", "{\"summary\":\"ok\"}", "https://x/y", receivedAt);

        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(model);

        assertThat(dto.taskId()).isEqualTo(taskId);
        assertThat(dto.agentStatus()).isEqualTo("COMPLETED");
        assertThat(dto.resultPayloadJson()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(dto.resultUrl()).isEqualTo("https://x/y");
        assertThat(dto.receivedAt()).isEqualTo(receivedAt);
    }

    @Test
    void mapsNullResultUrl() {
        TaskResultModel model = TaskResultModel.rehydrate(
                UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", "{}", null,
                Instant.parse("2026-06-06T10:15:30Z"));

        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(model);

        assertThat(dto.resultUrl()).isNull();
    }
}
