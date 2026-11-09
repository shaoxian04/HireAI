package com.hireai.domain.biz.task.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Task aggregate root. A task is the unit of work a client submits; its budget is
 * frozen in escrow at submission. Behaviour (the submit factory + the routing/execution
 * transitions) lives here, not in setters. The aggregate is framework-free and IMMUTABLE:
 * every transition returns a NEW copy and an illegal transition throws a {@link DomainException}.
 */
public final class TaskModel {

    private final UUID id;
    private final UUID clientId;
    private final String title;
    private final String description;
    private final Money budget;
    private final OutputSpec outputSpec;
    private final String category;
    private final TaskStatus status;
    private final UUID agentVersionId; // nullable until assigned
    private final TaskResultModel result; // nullable until a result is recorded
    private final Instant createdAt;
    private final TaskResolution resolution;   // nullable until RESOLVED
    private final Instant resolvedAt;          // nullable until RESOLVED
    private final String rejectionReason;      // nullable; only set on REJECTED
    private final RejectReason rejectReasonCategory; // nullable; A/B/C/D once reviewed via reject
    private final FailureReason failureReason; // nullable; set once when the task ends without a delivered result

    // Must stay in sync with the V9 rejection_reason DB CHECK and RejectTaskRequest's @Size(max).
    private static final int MAX_REASON_LENGTH = 500;

    /** Canonical 16-arg constructor: used by the rehydration path and all transition helpers. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt,
                     TaskResolution resolution, Instant resolvedAt, String rejectionReason,
                     RejectReason rejectReasonCategory, FailureReason failureReason) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.category = category;
        this.status = status;
        this.agentVersionId = agentVersionId;
        this.result = result;
        this.createdAt = createdAt;
        this.resolution = resolution;
        this.resolvedAt = resolvedAt;
        this.rejectionReason = rejectionReason;
        this.rejectReasonCategory = rejectReasonCategory;
        this.failureReason = failureReason;
    }

    /** Pre-review rehydration overload (11-arg): delegates to the canonical constructor. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt) {
        this(id, clientId, title, description, budget, outputSpec, category, status,
                agentVersionId, result, createdAt, null, null, null, null, null);
    }

    /** Factory for the SUBMIT transition: enforces invariants and creates a SUBMITTED task. */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec, String category) {
        requirePresent(clientId, "client id");
        requireText(title, "title");
        requireText(description, "description");
        requirePositive(budget);
        requirePresent(outputSpec, "output spec");
        requireText(category, "category");
        // Lowercase to match AgentVersionModel.normaliseCategories' .trim().toLowerCase() idiom,
        // so the routing re-filter's case-sensitive containment check (coversCategory) actually matches.
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, category.trim().toLowerCase(), TaskStatus.SUBMITTED, null, null, Instant.now());
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Budget must be positive");
        }
    }

    // --- Routing & execution transitions (immutable: each returns a NEW TaskModel) ---

    /** SUBMITTED/AWAITING_AGENT → QUEUED: a matching agent version was selected (re-match included). */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatusIn("assignAndQueue", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, agentVersionId, this.result);
    }

    /**
     * QUEUED → EXECUTING: the WorkOrder reached the agent. Idempotent: from any state past
     * QUEUED (the agent already called back, or the task timed out / failed meanwhile) it is a
     * no-op that returns {@code this}. Only the pre-assignment states (SUBMITTED,
     * AWAITING_AGENT) are illegal sources.
     */
    public TaskModel markExecuting() {
        if (this.status == TaskStatus.SUBMITTED || this.status == TaskStatus.AWAITING_AGENT) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Illegal transition markExecuting from " + this.status);
        }
        if (this.status != TaskStatus.QUEUED) {
            return this;
        }
        return copyWith(TaskStatus.EXECUTING, this.agentVersionId, this.result);
    }

    /**
     * QUEUED/EXECUTING → RESULT_RECEIVED: the agent posted a result back. QUEUED is allowed
     * because a fast agent may call back before the dispatch consumer marks the task EXECUTING.
     */
    public TaskModel recordResult(TaskResultModel result) {
        requireStatusIn("recordResult", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        requirePresent(result, "result");
        return copyWith(TaskStatus.RESULT_RECEIVED, this.agentVersionId, result);
    }

    /** RESULT_RECEIVED → PENDING_REVIEW: automated validation passed; awaits client review. */
    public TaskModel passValidation() {
        requireStatus(TaskStatus.RESULT_RECEIVED, "passValidation");
        return copyWith(TaskStatus.PENDING_REVIEW, this.agentVersionId, this.result);
    }

    /** RESULT_RECEIVED → SPEC_VIOLATION: automated validation failed; result is rejected by the gate. */
    public TaskModel failValidation() {
        requireStatus(TaskStatus.RESULT_RECEIVED, "failValidation");
        return endWithoutResult(TaskStatus.SPEC_VIOLATION, FailureReason.SPEC_VIOLATION);
    }

    /** SUBMITTED → AWAITING_AGENT (idempotent from AWAITING_AGENT for re-match no-match passes). */
    public TaskModel markAwaitingAgent() {
        requireStatusIn("markAwaitingAgent", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        return copyWith(TaskStatus.AWAITING_AGENT, this.agentVersionId, this.result);
    }

    /**
     * SUBMITTED|AWAITING_AGENT → CANCELLED: routing attempts exhausted before any Agent was
     * assigned (the SUBMITTED sweeper or the re-match sweeper); the caller refunds escrow and
     * names why no Agent was reached.
     */
    public TaskModel markCancelled(FailureReason reason) {
        requireStatusIn("markCancelled", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        return endWithoutResult(TaskStatus.CANCELLED, reason);
    }

    /**
     * QUEUED/EXECUTING → TIMED_OUT: no result before the execution deadline. The reason follows
     * {@link #timeoutIsAgentFault()}: EXECUTION_TIMEOUT once the Agent had the work, otherwise
     * DISPATCH_ABANDONED (the WorkOrder never got through).
     */
    public TaskModel markTimedOut() {
        requireStatusIn("markTimedOut", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return endWithoutResult(TaskStatus.TIMED_OUT,
                timeoutIsAgentFault() ? FailureReason.EXECUTION_TIMEOUT : FailureReason.DISPATCH_ABANDONED);
    }

    /**
     * Whether a timeout now is the Agent's fault: only once the Agent accepted the WorkOrder
     * (EXECUTING). A task still QUEUED never reached it (lost or undelivered message), so a
     * timeout from there refunds the client without penalising the Agent (#66).
     */
    public boolean timeoutIsAgentFault() {
        return this.status == TaskStatus.EXECUTING;
    }

    /** QUEUED/EXECUTING → FAILED: dispatch/execution failed terminally, for the given reason. */
    public TaskModel markFailed(FailureReason reason) {
        requireStatusIn("markFailed", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return endWithoutResult(TaskStatus.FAILED, reason);
    }

    /** PENDING_REVIEW → RESOLVED (ACCEPTED): the client accepted the result. */
    public TaskModel accept() {
        requireStatus(TaskStatus.PENDING_REVIEW, "accept");
        return resolved(TaskResolution.ACCEPTED, null);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): the client rejected the result. Reason optional, ≤500 chars. */
    public TaskModel reject(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "reject");
        return resolved(TaskResolution.REJECTED, trimReason(reason));
    }

    /** PENDING_REVIEW → DISPUTED: client rejected with a disputable reason (A/B/C); arbitration opens. */
    public TaskModel dispute(RejectReason reasonCategory, String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "dispute");
        requirePresent(reasonCategory, "reject reason category");
        if (!reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "dispute() requires an A/B/C reason; got " + reasonCategory);
        }
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.DISPUTED, agentVersionId, result, createdAt,
                this.resolution, this.resolvedAt, trimReason(reason), reasonCategory, this.failureReason);
    }

    /** DISPUTED → RESOLVED: the arbitration ruling (or fallback) has been settled. */
    public TaskModel resolveDispute(TaskResolution resolution) {
        requireStatus(TaskStatus.DISPUTED, "resolveDispute");
        requirePresent(resolution, "resolution");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                resolution, Instant.now(), this.rejectionReason, this.rejectReasonCategory, this.failureReason);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): client changed their mind (D); charged in full, no dispute. */
    public TaskModel chargeChangedMind(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "chargeChangedMind");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                TaskResolution.REJECTED, Instant.now(), trimReason(reason), RejectReason.D_CHANGED_MIND,
                this.failureReason);
    }

    private static String trimReason(String reason) {
        String trimmed = (reason == null || reason.isBlank()) ? null : reason.trim();
        if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Rejection reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    private TaskModel resolved(TaskResolution resolution, String rejectionReason) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, TaskStatus.RESOLVED, this.agentVersionId, this.result,
                this.createdAt, resolution, Instant.now(), rejectionReason, this.rejectReasonCategory,
                this.failureReason);
    }

    private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, newStatus, newAgentVersionId, newResult, this.createdAt,
                this.resolution, this.resolvedAt, this.rejectionReason, this.rejectReasonCategory,
                this.failureReason);
    }

    /** Terminal transition without a delivered result: records the FailureReason, exactly once. */
    private TaskModel endWithoutResult(TaskStatus terminalStatus, FailureReason reason) {
        requirePresent(reason, "failure reason");
        if (this.failureReason != null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Failure reason already set to " + this.failureReason + "; cannot record " + reason);
        }
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, terminalStatus, this.agentVersionId, this.result,
                this.createdAt, this.resolution, this.resolvedAt, this.rejectionReason,
                this.rejectReasonCategory, reason);
    }

    private void requireStatus(TaskStatus expected, String transition) {
        if (this.status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Illegal transition " + transition + " from " + this.status + "; expected " + expected);
        }
    }

    private void requireStatusIn(String transition, TaskStatus... allowed) {
        for (TaskStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                "Illegal transition " + transition + " from " + this.status);
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public String title() { return title; }
    public String description() { return description; }
    public Money budget() { return budget; }
    public OutputSpec outputSpec() { return outputSpec; }
    public String category() { return category; }
    public TaskStatus status() { return status; }
    public UUID agentVersionId() { return agentVersionId; }
    public TaskResultModel result() { return result; }
    public Instant createdAt() { return createdAt; }
    public TaskResolution resolution() { return resolution; }
    public Instant resolvedAt() { return resolvedAt; }
    public String rejectionReason() { return rejectionReason; }
    public RejectReason rejectReasonCategory() { return rejectReasonCategory; }
    public FailureReason failureReason() { return failureReason; }
}
