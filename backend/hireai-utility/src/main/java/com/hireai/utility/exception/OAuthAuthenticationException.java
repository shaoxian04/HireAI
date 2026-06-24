package com.hireai.utility.exception;

/** Raised when an OAuth login cannot proceed (e.g. unverified email). Drives the failure redirect. */
public class OAuthAuthenticationException extends RuntimeException {

    public OAuthAuthenticationException(String message) {
        super(message);
    }
}
