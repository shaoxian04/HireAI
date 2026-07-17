package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Production seam: the {@link ApiKeyContext} is present iff {@link ApiKeyAuthenticationFilter} set it. */
@Component
@Profile("!test")
public class HttpCurrentApiKeyProvider implements CurrentApiKeyProvider {

    @Override
    public Optional<ApiKeyContext> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof ApiKeyContext ctx) {
            return Optional.of(ctx);
        }
        return Optional.empty();
    }
}
