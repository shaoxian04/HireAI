package com.hireai.controller.biz.acceptance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * An inbound Contract, in the same shape {@link ContractDTO} returns: a Template version plus parameter
 * values (a number for {@code max}/{@code min}, a string for {@code enum}, an array of strings for
 * {@code subset}, a boolean for {@code flag}). Bounds are checked against the Template by the service.
 */
public record ContractRequest(
        @NotBlank @Size(max = 64) String template,
        @NotNull @Positive Integer version,
        @Size(max = 32) Map<String, Object> params
) {
}
