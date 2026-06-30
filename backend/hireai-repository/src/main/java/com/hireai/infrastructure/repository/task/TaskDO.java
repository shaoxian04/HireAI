package com.hireai.infrastructure.repository.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for a task. Separate from the domain {@code TaskModel} so the
 * domain stays framework-free. {@code output_spec} is stored as JSONB; the repository
 * impl serialises the {@code OutputSpec} value object to/from JSON. {@code agentVersionId}
 * is a plain UUID (no FK — the Agent context is independent).
 */
@Entity
@Table(name = "tasks")
public class TaskDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "budget", nullable = false)
    private BigDecimal budget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private String outputSpec;

    @Column(name = "category")
    private String category;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "agent_version_id")
    private UUID agentVersionId;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "reject_reason_category")
    private String rejectReasonCategory;

    protected TaskDO() {
    }

    public TaskDO(UUID id, UUID clientId, String title, String description,
                         BigDecimal budget, String outputSpec, String category, String status,
                         UUID agentVersionId, Instant gmtCreate,
                         String resolution, Instant resolvedAt, String rejectionReason,
                         String rejectReasonCategory) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.category = category;
        this.status = status;
        this.agentVersionId = agentVersionId;
        this.gmtCreate = gmtCreate;
        this.resolution = resolution;
        this.resolvedAt = resolvedAt;
        this.rejectionReason = rejectionReason;
        this.rejectReasonCategory = rejectReasonCategory;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public BigDecimal getBudget() { return budget; }
    public String getOutputSpec() { return outputSpec; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public UUID getAgentVersionId() { return agentVersionId; }
    public Instant getGmtCreate() { return gmtCreate; }
    public String getResolution() { return resolution; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public String getRejectReasonCategory() { return rejectReasonCategory; }
}
