package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Inbound HTTP DTO for a match preview. A POST body, so the Client's task text never travels in a
 * URL (and never lands in an access log). The field limits mirror {@link SubmitTaskRequest}.
 * Nothing is created or frozen by a preview.
 */
public record MatchPreviewRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotBlank @Size(max = 100) String category,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        /** Optional: how many seconds from now the Client needs the result. */
        @Positive(message = "deadlineSeconds must be positive") Long deadlineSeconds,
        /** Optional: Agents to leave out of both lists (the web rebook passes the one that failed). */
        List<@NotNull UUID> exclude
) {
}
