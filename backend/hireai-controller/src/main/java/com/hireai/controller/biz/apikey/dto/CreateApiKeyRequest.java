package com.hireai.controller.biz.apikey.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Create a key: a human label, plus two optional per-key caps. */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String name,
        @DecimalMin(value = "0.01", message = "spendCap must be positive")
        @Digits(integer = 16, fraction = 2) BigDecimal spendCap,
        @DecimalMin(value = "0.01", message = "dailySpendCap must be positive")
        @Digits(integer = 16, fraction = 2) BigDecimal dailySpendCap
) {}
