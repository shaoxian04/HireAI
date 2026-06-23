package com.hireai.application.port.security;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

/**
 * Application port for issuing and verifying authentication JWTs (Hard Invariant #5). The token is
 * bound to a user id + the user's role set with a bounded TTL; the {@code JwtAuthenticationFilter}
 * verifies it on every protected request. HS256-backed impl lives in {@code infrastructure/security}.
 */
public interface JwtService {

    String issue(UUID userId, Collection<String> roles, Duration ttl);

    JwtPrincipal verify(String token);
}
