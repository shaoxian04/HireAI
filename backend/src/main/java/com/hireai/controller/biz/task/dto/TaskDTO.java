package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound HTTP DTO for a task. No domain types leak across the boundary. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDTO(
        UUID id,
        UUID clientId,
        String title,
        String description,
        BigDecimal budget,
        String status,
        OutputSpecDTO outputSpec,
        Instant createdAt,
        String resolution,
        Instant resolvedAt,
        String rejectionReason,
        BigDecimal payoutAmount,
        BigDecimal commissionAmount,
        BigDecimal refundAmount
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
