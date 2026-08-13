package com.hireai.application.biz.reputation;

import com.hireai.domain.biz.reputation.model.ReviewModel;

import java.util.UUID;

/**
 * The client-authored half of reputation: rating work you received.
 *
 * <p>A rating is the only thing feeding Satisfaction, so it steers routing — which is why it is
 * permitted only where the client accepted the work and paid for it in full. Disputed tasks are
 * deliberately not rateable: the dispute was the client's formal channel, and reopening it
 * informally would either let a losing client retaliate against the ruling or punish the same
 * failure twice across two components.
 */
public interface ReviewAppService {

    /**
     * Records a client's rating of the task they accepted, exactly once.
     *
     * @throws com.hireai.utility.exception.DomainException NOT_FOUND if the task does not exist or
     *         is not the caller's (a foreign task is indistinguishable from a missing one);
     *         VALIDATION_ERROR if the task was not accepted, was submitted programmatically, is
     *         the caller's own agent, or has already been rated
     */
    ReviewModel review(UUID taskId, UUID clientId, int rating, String reviewText);
}
