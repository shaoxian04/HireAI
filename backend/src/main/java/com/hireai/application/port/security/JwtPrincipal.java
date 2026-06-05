package com.hireai.application.port.security;

import java.util.UUID;

/**
 * Verified identity carried by an authentication JWT: the user id (subject) and role. Returned by
 * {@link JwtService#verify(String)} once signature and expiry pass. The filter turns this into the
 * Spring {@code SecurityContext} principal (Hard Invariant #5).
 */
public record JwtPrincipal(UUID userId, String role) {
}
