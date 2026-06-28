package com.hireai.controller.biz.agent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request body for POST /api/agents/{agentId}/versions — publishes a NEW agent version that
 * supersedes the current one. outputSpec + webhookUrl carry over from the current version; only
 * the commercials (price / maxExecutionSeconds / capabilityCategories) are re-declared.
 */
public record PublishVersionRequest(
        @NotNull @DecimalMin("0.00")
        @Digits(integer = 12, fraction = 2) BigDecimal price,
        @Min(1) int maxExecutionSeconds,
        @NotEmpty List<String> capabilityCategories) {
}
