package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One Item of a multi-Item Task (spec S3) and its outcome. Amounts appear once the Item settled:
 * an accepted Item pays the builder {@code payoutAmount} and the platform {@code commissionAmount};
 * a refunded one returns {@code refundAmount} to the Client; a pro-rata one does both, in part.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemDTO(
        int itemNo,
        String input,
        /**
         * AWAITING_RESULT, PENDING_REVIEW, ACCEPTED, SPEC_VIOLATION, FAILED, REJECTED (a reviewer found it
         * wrong; on its rejection response path), CONCEDED (refunded) or PRO_RATA (paid in part, D19).
         */
        String status,
        BigDecimal price,
        String resultPayload,
        String resultUrl,
        List<CheckDTO> checks,
        Boolean autoAccepted,
        BigDecimal payoutAmount,
        BigDecimal commissionAmount,
        BigDecimal refundAmount,
        /** The SourceSnapshot this Item's input is (an Engagement's document); absent for a text Item. */
        UUID sourceId,
        /**
         * True when a check sent this Item to the buyer's review (D7): the reviewer judges it on its own, outside
         * the sample, and it is paid only when confirmed (or when the review window closes). Absent otherwise.
         */
        Boolean needsReview,
        /** Facts about the Item's input (duplicates, figures that don't add up, D45); never change the pay. */
        List<ValidationReportDTO.FindingDTO> findings,
        /** What the Agent self-reported about this Item's source with its result (D7). */
        List<ValidationReportDTO.AnomalyDTO> anomalies
) {

    /** One Acceptance Check as run on this Item's result: outcome PASS, REJECT or REVIEW. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckDTO(String rule, boolean passed, String detail, String outcome) {
    }
}
