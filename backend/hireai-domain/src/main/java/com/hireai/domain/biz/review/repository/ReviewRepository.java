package com.hireai.domain.biz.review.repository;

import com.hireai.domain.biz.review.model.ReviewModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

    ReviewModel save(ReviewModel review);

    Optional<ReviewModel> findById(UUID reviewId);

    /** Published reviews for an agent, newest first. */
    List<ReviewModel> findPublishedByAgentId(UUID agentId, int limit);
}
