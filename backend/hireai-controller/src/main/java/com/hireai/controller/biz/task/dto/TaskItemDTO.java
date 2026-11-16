package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
        UUID sourceId
) {

    /** One Acceptance Check as run on this Item's result. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckDTO(String rule, boolean passed, String detail) {
    }
}
