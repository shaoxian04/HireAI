package com.hireai.application.port.aireview;

/**
 * The reviewer gave no usable verdict. Never a pass: the review fails closed. {@code retryable} is
 * true for a transient failure (down, timeout, 5xx, not configured), retried with a bounded backoff
 * up to the attempt limit; false for an answer that came back unusable (malformed, a criterion missing
 * or judged twice, no reason, a refusal), which is retried once only, since the Agent's deliverable
 * may provoke it on purpose.
 */
public class AiReviewerUnavailableException extends RuntimeException {

    private final boolean retryable;

    public AiReviewerUnavailableException(String message) {
        this(message, true, null);
    }

    public AiReviewerUnavailableException(String message, Throwable cause) {
        this(message, true, cause);
    }

    private AiReviewerUnavailableException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** An answer that came back unusable: not retried. */
    public static AiReviewerUnavailableException unusableAnswer(String message) {
        return new AiReviewerUnavailableException(message, false, null);
    }

    public boolean retryable() {
        return retryable;
    }
}
