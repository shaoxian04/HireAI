package com.hireai.application.biz.auth;

/** Normalized identity claims extracted from the OAuth provider's userinfo. */
public record OAuthUserInfo(String provider, String subject, String email,
                            boolean emailVerified, String displayName) {
}
