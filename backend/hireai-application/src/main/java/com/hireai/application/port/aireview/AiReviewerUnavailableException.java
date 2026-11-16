package com.hireai.application.port.aireview;

/** The reviewer gave no usable verdict; the review is retried, then fails with its fee refunded. */
public class AiReviewerUnavailableException extends RuntimeException {

    public AiReviewerUnavailableException(String message) {
        super(message);
    }

    public AiReviewerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
