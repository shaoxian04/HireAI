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
        BigDecimal refundAmount,
        /** Why the task ended without a delivered result (FailureReason name); absent otherwise. */
        String failureReason,
        /** The capability category the task was matched on (lowercase). */
        String category,
        /**
         * The Agent the task was booked with or assigned to; absent when none was (a routed task
         * that never found one) and on list/command responses, which don't resolve it.
         */
        UUID agentId
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
