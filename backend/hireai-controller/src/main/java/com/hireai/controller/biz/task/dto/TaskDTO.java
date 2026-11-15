package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.controller.biz.acceptance.dto.ContractDTO;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
         * When the Client's review window closes (#94): set once the result awaits review on the
         * web channel, and kept after it is resolved. Absent on list/command responses.
         */
        Instant reviewDeadline,
        /** True when the window closed unreviewed and the platform accepted on the Client's behalf. */
        Boolean autoAccepted,
        /**
         * The latest round of the rejection response path (#95), once the Client rejected for reason
         * A/B/C; kept after it is settled. Absent otherwise and on list/command responses.
         */
        RejectionDTO rejection,
        /**
         * A multi-Item Task (spec S3): how many Items, the price of one ({@code budget} is the
         * escrow, price × count), and each Item with its outcome. All three are absent on a
         * single-deliverable Task; {@code items} is also absent on list responses.
         */
        Integer itemCount,
        BigDecimal itemPrice,
        List<TaskItemDTO> items
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
