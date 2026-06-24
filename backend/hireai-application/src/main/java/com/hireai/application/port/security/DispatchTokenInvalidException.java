package com.hireai.application.port.security;

/**
 * Thrown by {@link DispatchTokenService#verify(String)} when a dispatch token has a bad
 * signature, is expired, or does not match the expected task/agent version. The callback
 * controller (Plan 3) maps this to HTTP 401 (Hard Invariant #6).
 */
public class DispatchTokenInvalidException extends RuntimeException {

    public DispatchTokenInvalidException(String message) {
        super(message);
    }
}
