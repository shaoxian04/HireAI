package com.hireai.controller.biz.auth.dto;

import jakarta.validation.constraints.AssertTrue;

/** Inbound DTO for the become-builder upgrade. The user must accept the builder terms. */
public record BecomeBuilderRequest(
        @AssertTrue(message = "must accept builder terms") boolean acceptTerms
) {
}
