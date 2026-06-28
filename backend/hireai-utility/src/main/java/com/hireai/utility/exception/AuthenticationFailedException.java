package com.hireai.utility.exception;

/**
 * Raised on ANY login failure — unknown email, wrong password, or inactive account. Deliberately
 * one exception with one generic message so the API never reveals which users exist (no
 * enumeration). The global exception handler maps it to HTTP 401.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password");
    }
}
