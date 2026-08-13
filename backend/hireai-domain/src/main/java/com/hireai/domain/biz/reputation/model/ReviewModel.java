package com.hireai.domain.biz.reputation.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's rating of an agent, always attached to the task it is about.
 *
 * <p>{@code taskId} is mandatory and unique (V28). Until Module 5 landed, reviews were fabricated
 * demo rows with no task linkage; the earned-review flow replaces them, so a review that names no
 * task is no longer representable. Every star on the site now belongs to work a client accepted
 * and paid for in full.
 *
 * <p>Immutable; {@link #respond(String)} returns a copy.
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
        requireIdentity(taskId, clientId, agentId);
        requireRating(rating);
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

    /**
     * A client rating the task they accepted. The only way a review comes into existence — there
     * is deliberately no factory for a review detached from a task.
     */
    public static ReviewModel forTask(UUID taskId, UUID clientId, UUID agentId, int rating,
                                      String reviewText) {
        return new ReviewModel(UUID.randomUUID(), taskId, clientId, agentId, rating,
                reviewText == null || reviewText.isBlank() ? null : reviewText.trim(),
                null, true, Instant.now());
    }

    private static void requireIdentity(UUID taskId, UUID clientId, UUID agentId) {
        if (taskId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "a review must name the task it is about");
        }
        if (clientId == null || agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "client id and agent id are required");
        }
    }

    private static void requireRating(int rating) {
        if (rating < 1 || rating > 5) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "rating must be between 1 and 5");
        }
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
