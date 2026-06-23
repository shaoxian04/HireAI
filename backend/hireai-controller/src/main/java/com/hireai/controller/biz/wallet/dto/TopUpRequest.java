package com.hireai.controller.biz.wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Inbound HTTP DTO for a wallet top-up. Bean Validation at the boundary. */
public record TopUpRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be positive")
        @Digits(integer = 12, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount
) {
}
