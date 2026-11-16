package com.hireai.controller.biz.acceptance.dto;

import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * An inbound Offer, in the shape {@link OfferDTO} returns minus the price (the version's own
 * {@code price} field sets it): per Template version, the range accepted for each parameter, one of
 * {@code {"bound":n}}, {@code {"oneOf":[...]}}, {@code {"any":true}} or {@code {"acceptsTrue":b}}.
 * Bounds are checked against the Templates by the service.
 */
public record OfferRequest(
        @NotEmpty @Size(max = 16) List<@NotNull @Valid TermRequest> terms,
        /** Optional (spec S10, D38): accept AI Review on this Agent's results; default false. */
        Boolean acceptsAiReview,
        /** The strictest sampling level a buyer may choose for a multi-Item Task (II or III); default II. */
        InspectionLevel strictestInspectionLevel
) {

    public OfferRequest(List<TermRequest> terms) {
        this(terms, null, null);
    }

    public record TermRequest(
            @NotBlank @Size(max = 64) String template,
            @NotNull @Positive Integer version,
            @Size(max = 32) Map<String, Map<String, Object>> params
    ) {
    }
}
