package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link CurrentUserProvider}: returns the authenticated user id placed in the
 * {@code SecurityContext} by {@link JwtAuthenticationFilter} (Hard Invariant #5 — identity comes only
 * from the verified JWT). Throws {@link IllegalStateException} if called without an authenticated
 * principal (a programming error: protected routes are gated by the security chain, so a controller
 * never runs unauthenticated). Active outside the {@code test} profile; the {@code test} profile uses
 * {@link DevCurrentUserProvider}.
 */
@Component
@Profile("!test")
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return userId;
    }
}
