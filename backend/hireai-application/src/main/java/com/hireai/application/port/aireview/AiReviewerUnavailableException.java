package com.hireai.application.port.aireview;

/**
 * The reviewer gave no usable verdict. Never a pass: the review fails closed, its fee is refunded and
 * the Task settles as it would have without AI Review. {@code retryable} is true for a transport
 * failure (down, timeout, not configured), which the sweeper tries again up to the attempt limit; an
 * answer that came back but is unusable (malformed, a criterion missing or judged twice, no reason, a
 * refusal) is not retried, since the same evidence would most likely get the same answer.
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
