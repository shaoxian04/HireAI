package com.hireai.application.biz.identity;

import com.hireai.utility.exception.AuthenticationFailedException;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates the login use case: look up the user by email, verify the BCrypt password, and issue
 * a JWT. Every failure path (unknown email / wrong password / inactive) throws a single
 * {@link AuthenticationFailedException} so the API leaks no user-existence information.
 */
@Validated
public interface AuthAppService {

    AuthResult login(@NonNull LoginInfo loginInfo);

    AuthResult register(@NonNull RegisterInfo registerInfo);

    AuthResult becomeBuilder(@NonNull UUID userId);
}
