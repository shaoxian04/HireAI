package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.domain.biz.task.model.TaskResultModel;

/**
 * Explicit, hand-written converter from the {@link TaskResultModel} child entity to its outbound
 * DTO. One direction only; no auto-mapping, so what crosses the boundary is deliberate. Mirrors
 * {@code TaskModel2DTOConverter}.
 */
public final class TaskResult2DTOConverter {

    private TaskResult2DTOConverter() {
    }

    public static TaskResultDTO toDTO(TaskResultModel result) {
        return new TaskResultDTO(
                result.taskId(),
                result.agentStatus(),
                result.resultPayloadJson(),
                result.resultUrl(),
                result.receivedAt());
    }
}
