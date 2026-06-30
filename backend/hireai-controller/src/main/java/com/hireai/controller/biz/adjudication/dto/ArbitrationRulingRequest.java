package com.hireai.controller.biz.adjudication.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the arbitration ruling callback.
 * {@code category} must match a {@link com.hireai.domain.biz.adjudication.enums.RulingCategory} value;
 * an unrecognised value is a caller error and maps to HTTP 400.
 */
public record ArbitrationRulingRequest(@NotBlank String category, String rationale) {}
