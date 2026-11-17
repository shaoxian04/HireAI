package com.hireai.controller.biz.acceptance.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * An agent version's Offer: the price (the version's price) and, per Template version it works
 * under, the range it accepts for each parameter. A range is one of {@code {"bound":n}}
 * ({@code max}/{@code min}), {@code {"oneOf":[...]}} or {@code {"any":true}} ({@code subset}/{@code enum}),
 * {@code {"acceptsTrue":b}} ({@code flag}). A parameter left out is accepted only when a Contract
 * leaves it unset. {@code acceptsAiReview}: Clients may book an AI Review of this Agent's results, which
 * on API/MCP settles the Task and is final (spec S10, D38).
 */
public record OfferDTO(BigDecimal price, List<TermDTO> terms, boolean acceptsAiReview,
                       String strictestInspectionLevel) {

    public record TermDTO(String template, int version, Map<String, Map<String, Object>> params) {
    }
}
