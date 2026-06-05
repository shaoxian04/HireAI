package com.hireai.application.biz.auth;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

/**
 * Orchestrates the login use case: look up the user by email, verify the BCrypt password, and issue
 * a JWT. Every failure path (unknown email / wrong password / inactive) throws a single
 * {@link AuthenticationFailedException} so the API leaks no user-existence information.
 */
@Validated
public interface AuthAppService {

    AuthResult login(@NonNull LoginInfo loginInfo);
}
