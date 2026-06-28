package com.hireai.domain.biz.review.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's rating of an agent. In this slice reviews are SEEDED (taskId nullable) because
 * the rate-a-settled-task flow needs Modules 4/5. Builder may set or replace a response via
 * respond(); each call returns a new copy with the updated response.
 * Immutable; respond() returns a copy.
 */
public final class ReviewModel {

    private final UUID id;
    private final UUID taskId;       // nullable for seeded reviews
    private final UUID clientId;
    private final UUID agentId;
    private final int rating;
    private final String reviewText;
    private final String builderResponse;
    private final boolean published;
    private final Instant createdAt;

    public ReviewModel(UUID id, UUID taskId, UUID clientId, UUID agentId, int rating,
                       String reviewText, String builderResponse, boolean published, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.clientId = clientId;
        this.agentId = agentId;
        this.rating = rating;
        this.reviewText = reviewText;
        this.builderResponse = builderResponse;
        this.published = published;
        this.createdAt = createdAt;
    }

    /** Factory for demo-seeded reviews (no task linkage). */
    public static ReviewModel seeded(UUID clientId, UUID agentId, int rating, String reviewText) {
        if (clientId == null || agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "client id and agent id are required");
        }
        if (rating < 1 || rating > 5) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "rating must be between 1 and 5");
        }
        return new ReviewModel(UUID.randomUUID(), null, clientId, agentId, rating,
                reviewText, null, true, Instant.now());
    }

    /** Builder sets or replaces their response to the review. Returns a new ReviewModel copy. */
    public ReviewModel respond(String response) {
        if (response == null || response.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "response must not be blank");
        }
        return new ReviewModel(id, taskId, clientId, agentId, rating,
                reviewText, response.trim(), published, createdAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID clientId() { return clientId; }
    public UUID agentId() { return agentId; }
    public int rating() { return rating; }
    public String reviewText() { return reviewText; }
    public String builderResponse() { return builderResponse; }
    public boolean published() { return published; }
    public Instant createdAt() { return createdAt; }
}
