package com.hireai.utility.exception;

/**
 * Thrown by {@code DispatchTokenService.verify(...)} when a dispatch token has a bad signature,
 * is expired, or does not match the expected task/agent version. The callback controller maps
 * this to HTTP 401 (Hard Invariant #6).
 */
public class DispatchTokenInvalidException extends RuntimeException {

    public DispatchTokenInvalidException(String message) {
        super(message);
    }
}
