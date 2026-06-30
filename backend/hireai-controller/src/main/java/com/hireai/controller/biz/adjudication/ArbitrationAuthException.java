package com.hireai.controller.biz.adjudication;

/**
 * Thrown when the arbitration callback request is missing a bearer token, the token
 * is blank, or the token does not match the configured shared secret.
 * Handled locally in {@link ArbitrationCallbackController} and mapped to HTTP 401.
 */
public class ArbitrationAuthException extends RuntimeException {

    public ArbitrationAuthException(String message) {
        super(message);
    }
}
