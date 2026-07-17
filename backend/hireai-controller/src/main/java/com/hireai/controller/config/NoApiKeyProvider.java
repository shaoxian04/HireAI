package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Test seam: no API-key context (controller tests mock it explicitly when they need one). */
@Component
@Profile("test")
public class NoApiKeyProvider implements CurrentApiKeyProvider {

    @Override
    public Optional<ApiKeyContext> current() {
        return Optional.empty();
    }
}
