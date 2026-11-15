package com.hireai.infrastructure.repository.task.rejection;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for a task_rejections row (V41). The notice-delivery and replaced-result columns are
 * not mapped: they are written by native updates only, so a full-row save never overwrites them.
 */
@Entity
@Table(name = "task_rejections")
public class RejectionDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "round", nullable = false)
    private int round;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false)
    private RejectReason reasonCategory;

    @Column(name = "reason")
    private String reason;

    @Column(name = "rejected_at", nullable = false)
    private Instant rejectedAt;

    @Column(name = "response_deadline", nullable = false)
    private Instant responseDeadline;

    @Column(name = "revision_allowed", nullable = false)
    private boolean revisionAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "response")
    private AgentResponse response;

    @Column(name = "response_reason")
    private String responseReason;

    @Column(name = "response_silent", nullable = false)
    private boolean responseSilent;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "decision_deadline")
    private Instant decisionDeadline;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision")
    private ClientDecision decision;

    @Column(name = "decision_silent", nullable = false)
    private boolean decisionSilent;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected RejectionDO() {
    }

    public RejectionDO(UUID id, UUID taskId, int round, RejectReason reasonCategory, String reason,
                       Instant rejectedAt, Instant responseDeadline, boolean revisionAllowed,
                       AgentResponse response, String responseReason, boolean responseSilent,
                       Instant respondedAt, Instant decisionDeadline, ClientDecision decision,
                       boolean decisionSilent, Instant decidedAt) {
        this.id = id;
        this.taskId = taskId;
        this.round = round;
        this.reasonCategory = reasonCategory;
        this.reason = reason;
        this.rejectedAt = rejectedAt;
        this.responseDeadline = responseDeadline;
        this.revisionAllowed = revisionAllowed;
        this.response = response;
        this.responseReason = responseReason;
        this.responseSilent = responseSilent;
        this.respondedAt = respondedAt;
        this.decisionDeadline = decisionDeadline;
        this.decision = decision;
        this.decisionSilent = decisionSilent;
        this.decidedAt = decidedAt;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public int getRound() { return round; }
    public RejectReason getReasonCategory() { return reasonCategory; }
    public String getReason() { return reason; }
    public Instant getRejectedAt() { return rejectedAt; }
    public Instant getResponseDeadline() { return responseDeadline; }
    public boolean isRevisionAllowed() { return revisionAllowed; }
    public AgentResponse getResponse() { return response; }
    public String getResponseReason() { return responseReason; }
    public boolean isResponseSilent() { return responseSilent; }
    public Instant getRespondedAt() { return respondedAt; }
    public Instant getDecisionDeadline() { return decisionDeadline; }
    public ClientDecision getDecision() { return decision; }
    public boolean isDecisionSilent() { return decisionSilent; }
    public Instant getDecidedAt() { return decidedAt; }
}
