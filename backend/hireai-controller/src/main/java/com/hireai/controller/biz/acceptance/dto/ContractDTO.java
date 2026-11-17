package com.hireai.controller.biz.acceptance.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A Task's frozen Contract: the Template version it is judged under and the parameter values.
 * Each value is natural JSON: a number ({@code max}/{@code min}), a string ({@code enum}), an array
 * of strings ({@code subset}) or a boolean ({@code flag}). The Template's labels and comparisons are
 * served by {@code GET /api/catalogue/templates/{key}/{version}}. {@code aiReview} is the AI Review the
 * Client booked with its frozen fee (spec S10), absent when there is none.
 */
public record ContractDTO(String template, int version, Map<String, Object> params,
                          @JsonInclude(JsonInclude.Include.NON_NULL) AiReviewTermsDTO aiReview) {

    public ContractDTO(String template, int version, Map<String, Object> params) {
        this(template, version, params, null);
    }

    public record AiReviewTermsDTO(List<String> criteria, BigDecimal fee) {
    }
}
