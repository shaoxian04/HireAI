package com.hireai.application.biz.auth;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

/**
 * Resolves an OAuth identity to a local account and issues our JWT (Hard Invariant #5). Resolution
 * order: existing identity link → existing email (link) → new CLIENT account. Email-based linking is
 * only safe because the provider verifies the email.
 */
@Validated
public interface OAuthAppService {

    AuthResult loginWithOAuth(@NonNull OAuthUserInfo info);
}
