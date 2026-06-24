package com.hireai.controller.biz.auth.dto;

import java.util.List;
import java.util.UUID;

/** Outbound HTTP DTO for a successful login/registration: bearer token + identity + role names. */
public record LoginResponse(String token, UUID userId, List<String> roles) {
}
