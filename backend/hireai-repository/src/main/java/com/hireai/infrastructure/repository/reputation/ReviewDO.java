package com.hireai.infrastructure.repository.reputation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for the reviews table. Separate from the domain model so the domain
 * stays framework-free. task_id is nullable (seeded reviews have no task linkage until
 * Modules 4/5 land).
 */
@Entity
@Table(name = "reviews")
public class ReviewDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "review_text")
    private String reviewText;

    @Column(name = "builder_response")
    private String builderResponse;

    @Column(name = "is_published", nullable = false)
    private boolean isPublished;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    @Column(name = "gmt_modified", nullable = false)
    private Instant gmtModified;

    protected ReviewDO() {
    }

    public ReviewDO(UUID id, UUID taskId, UUID clientId, UUID agentId, short rating,
                           String reviewText, String builderResponse, boolean isPublished,
                           Instant gmtCreate, Instant gmtModified) {
        this.id = id;
        this.taskId = taskId;
        this.clientId = clientId;
        this.agentId = agentId;
        this.rating = rating;
        this.reviewText = reviewText;
        this.builderResponse = builderResponse;
        this.isPublished = isPublished;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getClientId() { return clientId; }
    public UUID getAgentId() { return agentId; }
    public short getRating() { return rating; }
    public String getReviewText() { return reviewText; }
    public String getBuilderResponse() { return builderResponse; }
    public boolean isPublished() { return isPublished; }
    public Instant getGmtCreate() { return gmtCreate; }
    public Instant getGmtModified() { return gmtModified; }
}
