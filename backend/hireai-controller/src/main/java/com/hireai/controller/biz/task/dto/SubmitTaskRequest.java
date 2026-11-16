package com.hireai.controller.biz.task.dto;

import com.hireai.controller.biz.acceptance.dto.ContractRequest;
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
import java.util.List;

/**
 * Inbound HTTP DTO for submitting a routed task. Bean Validation at the boundary. Two modes (spec S6,
 * D28): without {@code contract} the platform extracts the requirements of the description onto the
 * output spec's Template (text when {@code outputSpec} is left out) and adopts the result; with a
 * {@code contract} nothing is extracted and the Contract is the only basis for acceptance
 * ({@code outputSpec} may then be left out, or must be of the Contract's Template's shape).
 */
public record SubmitTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotBlank @Size(max = 100) String category,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @Valid OutputSpecRequest outputSpec,
        /** Optional: how many seconds from submit the Client needs the result (#83). */
        @Positive(message = "deadlineSeconds must be positive")
        @Max(value = ClientDeadline.MAX_SECONDS, message = "deadlineSeconds must be at most 2592000 (30 days)")
        Long deadlineSeconds,
        /**
         * Optional: the inputs of a multi-Item Task (spec S3), one per Item, 2 to 1000. With Items,
         * {@code budget} is the price of ONE Item and the Task freezes budget × the Item count.
         */
        @Size(max = 1000, message = "items: a Task can hold at most 1000 Items")
        List<@NotBlank(message = "items: an Item must not be blank")
             @Size(max = 5000, message = "items: an Item must have at most 5000 characters") String> items,
        /** Optional: mode 2, the structured Contract to freeze; no extraction is done. */
        @Valid ContractRequest contract
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
