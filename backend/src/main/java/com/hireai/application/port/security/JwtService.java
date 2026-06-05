package com.hireai.application.port.security;

import java.time.Duration;
import java.util.UUID;

/**
 * Application port for issuing and verifying authentication JWTs (Hard Invariant #5). The login app
 * service issues a token bound to a user id + role with a bounded TTL; the {@code JwtAuthenticationFilter}
 * verifies it on every protected request. The HS256-backed implementation lives in
 * {@code infrastructure/security}.
 */
public interface JwtService {

    String issue(UUID userId, String role, Duration ttl);

    JwtPrincipal verify(String token);
}
