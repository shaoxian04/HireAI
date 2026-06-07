package com.hireai.controller.biz.agent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for PUT /api/agents/{agentId}/pricing.
 * Updates price, maxExecutionSeconds and capabilityCategories of the current version in place.
 * outputSpec and webhookUrl are deliberately excluded (not editable in this slice, spec §9).
 */
public record UpdatePricingRequest(
        @NotNull @DecimalMin("0.00")
        @Digits(integer = 12, fraction = 2) BigDecimal price,
        @Min(1) int maxExecutionSeconds,
        @NotEmpty List<String> capabilityCategories) {
}
