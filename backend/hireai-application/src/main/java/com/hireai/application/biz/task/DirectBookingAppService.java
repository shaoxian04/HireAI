package com.hireai.application.biz.task;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewQuote;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.shared.model.Money;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Direct booking (spec §4.3): client hires a SPECIFIC agent. Validates the target is ACTIVE +
 * listed and budget >= price, freezes the client's terms (covered by the agent's Offer) or else the
 * agent's own terms as the binding Contract (Invariant #4), then submits with escrow freeze (Invariant #1) and a pinned dispatch that
 * SKIPS matching.
 */
@Validated
public interface DirectBookingAppService {

    UUID book(@NonNull DirectBookingInfo info);

    /**
     * What an AI Review of {@code criteria} criteria would cost on a booking of {@code agentId} at
     * {@code budget}, under {@code contract} (null = the Agent's own terms), and whether it is offered
     * (spec S10, D37). Same NOT_FOUND rules as {@link #book}.
     */
    AiReviewQuote quoteAiReview(@NonNull UUID agentId, Contract contract, @NonNull Money budget, int criteria);
}
