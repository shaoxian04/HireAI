package com.hireai.application.biz.apikey;

import java.util.Optional;

/** Authenticates a raw API key. Returns empty for any absent/invalid/revoked key (no leak). */
public interface ApiKeyAuthService {
    Optional<ApiKeyPrincipal> authenticate(String rawKey);
}
