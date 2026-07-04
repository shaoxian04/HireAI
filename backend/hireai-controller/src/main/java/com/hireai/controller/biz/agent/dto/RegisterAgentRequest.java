package com.hireai.controller.biz.agent.dto;

import com.hireai.domain.biz.task.enums.OutputFormat;
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

/** Inbound HTTP DTO for registering an agent + its first version. Bean Validation at the boundary. */
public record RegisterAgentRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Valid OutputSpecRequest outputSpec,
        @NotEmpty List<@NotBlank @Size(max = 100) String> capabilityCategories,
        @NotBlank @Size(max = 2000) String webhookUrl,
        @Min(value = 1, message = "maxExecutionSeconds must be positive") int maxExecutionSeconds,
        @NotNull
        @DecimalMin(value = "0.00", message = "price must be non-negative")
        @Digits(integer = 12, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price,
        @Min(value = 1, message = "maxConcurrent must be at least 1")
        @Max(value = 100, message = "maxConcurrent must be at most 100")
        Integer maxConcurrent
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
