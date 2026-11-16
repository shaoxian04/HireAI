package com.hireai.application.port.aireview;

import com.hireai.domain.biz.acceptance.aireview.model.CriterionVerdict;

import java.util.List;

/**
 * The AI Review reviewer (spec S10, D37): a configurable multimodal model that judges a frozen
 * deliverable against the Client's criteria. The spec fixes this interface, not the model; the
 * default adapter uses the arbitrator's provider. The reviewer only judges: what its verdict does to
 * money is decided deterministically in the domain from the ruling category (Invariant #3).
 */
public interface AiReviewerPort {

    /**
     * One pass or fail per criterion of {@code subject}, with reasons.
     *
     * @throws AiReviewerUnavailableException when no verdict could be had (not configured, down, slow
     *         past the timeout, or an unusable answer)
     */
    Answer review(AiReviewSubject subject);

    /** The reviewer's verdicts and the model that gave them. */
    record Answer(List<CriterionVerdict> verdicts, String model) {

        public Answer {
            verdicts = List.copyOf(verdicts);
        }
    }
}
