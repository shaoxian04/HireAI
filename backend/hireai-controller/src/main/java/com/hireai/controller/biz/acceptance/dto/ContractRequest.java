package com.hireai.controller.biz.acceptance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * An inbound Contract, in the same shape {@link ContractDTO} returns: a Template version plus parameter
 * values (a number for {@code max}/{@code min}, a string for {@code enum}, an array of strings for
 * {@code subset}, a boolean for {@code flag}). Bounds are checked against the Template by the service.
 *
 * <p>{@code checks} (optional) names the Check Library entries the Client relies on: each must be one
 * the Template lists, and {@code params} must switch it on, or the request is a 400 (spec D15). It is a
 * guard only: which checks run always follows from the Template and the parameters.
 */
public record ContractRequest(
        @NotBlank @Size(max = 64) String template,
        @NotNull @Positive Integer version,
        @Size(max = 32) Map<String, Object> params,
        @Size(max = 32) List<@NotBlank @Size(max = 64) String> checks
) {

    public ContractRequest(String template, Integer version, Map<String, Object> params) {
        this(template, version, params, null);
    }
}
