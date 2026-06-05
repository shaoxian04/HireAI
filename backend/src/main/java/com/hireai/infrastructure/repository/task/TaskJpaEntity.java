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
 * impl serialises the {@code OutputSpec} value object to/from JSON.
 */
@Entity
@Table(name = "tasks")
public class TaskJpaEntity {

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

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected TaskJpaEntity() {
    }

    public TaskJpaEntity(UUID id, UUID clientId, String title, String description,
                         BigDecimal budget, String outputSpec, String status, Instant gmtCreate) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.status = status;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public BigDecimal getBudget() { return budget; }
    public String getOutputSpec() { return outputSpec; }
    public String getStatus() { return status; }
    public Instant getGmtCreate() { return gmtCreate; }
}
