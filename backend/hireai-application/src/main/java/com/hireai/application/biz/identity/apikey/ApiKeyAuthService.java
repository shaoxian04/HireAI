package com.hireai.application.biz.identity.apikey;

import java.util.Optional;

/** Authenticates a raw API key. Returns empty for any absent/invalid/revoked key (no leak). */
public interface ApiKeyAuthService {
    Optional<ApiKeyPrincipal> authenticate(String rawKey);
}
