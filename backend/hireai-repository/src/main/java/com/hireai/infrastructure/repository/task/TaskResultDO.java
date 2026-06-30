package com.hireai.infrastructure.repository.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for the single result an Agent posts back for a task. Written and
 * read only through the Task aggregate root. {@code result_payload} is stored as TEXT: it is
 * untrusted agent output that may be malformed, and the validation gate (not the DB) judges
 * whether it is well-formed JSON. A jsonb column would reject invalid JSON before the gate runs.
 */
@Entity
@Table(name = "task_results")
public class TaskResultDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "result_payload", columnDefinition = "text", nullable = false)
    private String resultPayload;

    @Column(name = "result_url")
    private String resultUrl;

    @Column(name = "agent_status", nullable = false)
    private String agentStatus;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected TaskResultDO() {
    }

    public TaskResultDO(UUID id, UUID taskId, String resultPayload, String resultUrl,
                               String agentStatus, Instant receivedAt) {
        this.id = id;
        this.taskId = taskId;
        this.resultPayload = resultPayload;
        this.resultUrl = resultUrl;
        this.agentStatus = agentStatus;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public String getResultPayload() { return resultPayload; }
    public String getResultUrl() { return resultUrl; }
    public String getAgentStatus() { return agentStatus; }
    public Instant getReceivedAt() { return receivedAt; }
}
