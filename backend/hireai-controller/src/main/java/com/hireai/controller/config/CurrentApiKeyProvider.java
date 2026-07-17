package com.hireai.controller.config;

import java.util.Optional;

/**
 * Resolves the API-key context of the current request, if any. JWT (human) requests return empty.
 * Mirrors {@link CurrentUserProvider}; the prod impl reads the SecurityContext, the test impl is empty.
 */
public interface CurrentApiKeyProvider {
    Optional<ApiKeyContext> current();
}
