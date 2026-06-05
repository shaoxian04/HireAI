package com.hireai.controller.biz.task.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound HTTP DTO for a task. No domain types leak across the boundary. */
public record TaskDTO(
        UUID id,
        UUID clientId,
        String title,
        String description,
        BigDecimal budget,
        String status,
        OutputSpecDTO outputSpec,
        Instant createdAt
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
