package com.hireai.controller.biz.agent.dto;

import com.hireai.controller.biz.acceptance.dto.OfferRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/agents/{agentId}/versions — publishes a NEW agent version that
 * supersedes the current one. outputSpec + webhookUrl carry over from the current version; the
 * commercials (price / maxExecutionSeconds / capabilityCategories) are re-declared, and the Offer
 * may be edited.
 */
public record PublishVersionRequest(
        @NotNull @DecimalMin("0.00")
        @Digits(integer = 12, fraction = 2) BigDecimal price,
        @Min(1)
        @Max(value = 86_400, message = "maxExecutionSeconds must be at most 86400 (24 hours)")
        int maxExecutionSeconds,
        @NotEmpty List<@NotBlank @Size(max = 100) String> capabilityCategories,
        /** Optional free-text proposal for a bucket the category list lacks; stored for admins. */
        @Size(max = 100) String suggestedCategory,
        /**
         * Optional edited Offer for the new version (ADR 0009): the Template versions it accepts and the
         * range of each parameter. Omitted, the current version's Offer carries over. Its price is {@code price}.
         */
        @Valid OfferRequest offer) {

    /** Keeping the current Offer. */
    public PublishVersionRequest(BigDecimal price, int maxExecutionSeconds, List<String> capabilityCategories,
                                 String suggestedCategory) {
        this(price, maxExecutionSeconds, capabilityCategories, suggestedCategory, null);
    }
}
