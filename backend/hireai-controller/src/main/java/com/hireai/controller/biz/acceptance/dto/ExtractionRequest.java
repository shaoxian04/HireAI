package com.hireai.controller.biz.acceptance.dto;

import com.hireai.domain.biz.acceptance.extraction.enums.ExtractionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * The {@code extraction} of a {@code POST /api/contracts/draft} answer, sent back with a booking made
 * from that draft (web, D7): the record of what the Client was shown about each requirement when they
 * booked. Same shape as {@link ExtractionDTO}. It is a record only: the booking's {@code contract} is
 * what is frozen and checked, and the extraction must name that Contract's Template version.
 */
public record ExtractionRequest(
        @NotBlank @Size(max = 64) String template,
        @NotNull @Positive Integer version,
        @NotNull ExtractionStatus status,
        @Size(max = 64, message = "extraction.requirements: at most 64")
        List<@Valid @NotNull RequirementRequest> requirements
) {

    public record RequirementRequest(
            @NotBlank @Size(max = 2000) String text,
            boolean mapped,
            @Size(max = 64) String check,
            @Size(max = 32) Map<String, Object> params,
            @Size(max = 1000) String unmappedReason) {
    }
}
