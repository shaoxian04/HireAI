package com.hireai.application.port.security;

/**
 * Thrown by {@link JwtService#verify(String)} when an auth JWT has a bad signature, is malformed,
 * or is expired. The {@code JwtAuthenticationFilter} catches it and leaves the context
 * unauthenticated (the chain then 401s on protected routes).
 */
public class JwtInvalidException extends RuntimeException {

    public JwtInvalidException(String message) {
        super(message);
    }

    public JwtInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
