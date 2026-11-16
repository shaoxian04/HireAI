package com.hireai.domain.biz.acceptance.aireview.event;

import java.util.UUID;

/**
 * Published when a Task's result passed the deterministic checks and its AI Review is requested
 * (spec S10). After the validation commits, a listener runs the reviewer off the callback thread; the
 * AI Review sweeper retries one that did not finish.
 */
public record AiReviewRequestedDomainEvent(UUID taskId) {
}
