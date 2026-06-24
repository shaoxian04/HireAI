package com.hireai.application.port.security;

import java.util.Set;
import java.util.UUID;

/**
 * Verified identity carried by an authentication JWT: the user id (subject) and the role set.
 * Returned by {@link JwtService#verify(String)} once signature and expiry pass. The filter turns
 * each role into a {@code ROLE_<role>} authority (Hard Invariant #5).
 */
public record JwtPrincipal(UUID userId, Set<String> roles) {
}
