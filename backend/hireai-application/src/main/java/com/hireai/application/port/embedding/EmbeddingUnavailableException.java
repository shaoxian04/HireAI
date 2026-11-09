package com.hireai.application.port.embedding;

/** The embedding provider couldn't produce a vector right now; the caller carries on without one. */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
