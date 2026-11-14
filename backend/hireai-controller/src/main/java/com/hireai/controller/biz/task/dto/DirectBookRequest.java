package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import com.hireai.domain.biz.task.model.ClientDeadline;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound HTTP DTO for a direct booking: client hires a specific agent by id. */
public record DirectBookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull UUID agentId,
        /** Optional: a category slug the agent serves (default: the agent's first category). */
        @Size(max = 100) String category,
        /** Optional: how many seconds from booking the Client needs the result (#83). */
        @Positive(message = "deadlineSeconds must be positive")
        @Max(value = ClientDeadline.MAX_SECONDS, message = "deadlineSeconds must be at most 2592000 (30 days)")
        Long deadlineSeconds
) {
}
