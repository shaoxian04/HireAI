package com.hireai.application.biz.auth;

import java.util.UUID;

/** Result of a successful login: the signed JWT plus the resolved identity for the response body. */
public record AuthResult(String token, UUID userId, String role) {
}
