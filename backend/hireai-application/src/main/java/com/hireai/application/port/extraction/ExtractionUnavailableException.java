package com.hireai.application.port.extraction;

/** The extraction provider couldn't answer; the caller falls back to the Template's defaults (spec S6). */
public class ExtractionUnavailableException extends RuntimeException {

    public ExtractionUnavailableException(String message) {
        super(message);
    }

    public ExtractionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
