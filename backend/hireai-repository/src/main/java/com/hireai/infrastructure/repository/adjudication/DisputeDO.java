package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class DisputeDO {

    @Id @Column(name = "id") private UUID id;
    @Column(name = "task_id", nullable = false, unique = true) private UUID taskId;
    @Column(name = "raised_by", nullable = false) private UUID raisedBy;
    @Column(name = "reason_category", nullable = false) private String reasonCategory;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "correlation_id", nullable = false) private String correlationId;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "gmt_create", nullable = false) private Instant gmtCreate;

    protected DisputeDO() {}

    public DisputeDO(UUID id, UUID taskId, UUID raisedBy, String reasonCategory, String status,
                     String correlationId, Instant resolvedAt, Instant gmtCreate) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.correlationId = correlationId;
        this.resolvedAt = resolvedAt;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public UUID getRaisedBy() { return raisedBy; }
    public String getReasonCategory() { return reasonCategory; }
    public String getStatus() { return status; }
    public String getCorrelationId() { return correlationId; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getGmtCreate() { return gmtCreate; }
}
