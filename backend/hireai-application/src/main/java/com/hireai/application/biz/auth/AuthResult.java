package com.hireai.application.biz.auth;

import java.util.List;
import java.util.UUID;

/** Result of a successful auth: the signed JWT, the user id, and the resolved role names. */
public record AuthResult(String token, UUID userId, List<String> roles) {
}
