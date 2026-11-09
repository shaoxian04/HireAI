package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.OutputFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import com.hireai.domain.biz.task.model.ClientDeadline;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Inbound HTTP DTO for submitting a task. Bean Validation at the boundary. */
public record SubmitTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotBlank @Size(max = 100) String category,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull @Valid OutputSpecRequest outputSpec,
        /** Optional: how many seconds from submit the Client needs the result (#83). */
        @Positive(message = "deadlineSeconds must be positive")
        @Max(value = ClientDeadline.MAX_SECONDS, message = "deadlineSeconds must be at most 2592000 (30 days)")
        Long deadlineSeconds
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
