package com.hireai.controller.biz.agent.dto;

import com.hireai.domain.biz.review.model.ReviewModel;

import java.time.Instant;
import java.util.UUID;

public record ReviewDTO(UUID id, int rating, String reviewText, String builderResponse,
                        Instant createdAt) {

    public static ReviewDTO from(ReviewModel r) {
        return new ReviewDTO(r.id(), r.rating(), r.reviewText(), r.builderResponse(), r.createdAt());
    }
}
