package com.hireai.controller.biz.auth.dto;

import java.util.UUID;

/** Outbound HTTP DTO for a successful login: the bearer token plus the resolved identity. */
public record LoginResponse(String token, UUID userId, String role) {
}
