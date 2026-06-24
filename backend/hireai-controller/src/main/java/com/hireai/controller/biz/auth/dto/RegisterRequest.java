package com.hireai.controller.biz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Inbound HTTP DTO for registration. Bean Validation at the boundary. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        @Size(max = 120) String displayName
) {
}
