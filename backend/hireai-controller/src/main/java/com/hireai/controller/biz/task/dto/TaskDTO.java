package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.controller.biz.acceptance.dto.ContractDTO;

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
        /** The Contract frozen at submit (ADR 0009); absent on Tasks submitted before Contracts existed. */
        ContractDTO contract,
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
        UUID agentId,
        /**
         * Why no Agent matched the task (#91): the blocking Contract parameter, Template version or the
         * relevance floor, with Agent counts. Present while it waits AWAITING_AGENT and after it ends
         * NO_ELIGIBLE_AGENT; absent otherwise and on list/command responses.
         */
        String failureDetail
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
