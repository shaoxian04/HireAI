package com.hireai.domain.biz.task.model;

import com.hireai.utility.result.ResultCode;
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

    // Must stay in sync with the V9 rejection_reason DB CHECK and RejectTaskRequest's @Size(max).
    private static final int MAX_REASON_LENGTH = 500;

    /** Canonical 15-arg constructor: used by the rehydration path and all transition helpers. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt,
                     TaskResolution resolution, Instant resolvedAt, String rejectionReason,
                     RejectReason rejectReasonCategory) {
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
    }

    /** Pre-review rehydration overload (11-arg): delegates to the canonical constructor. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt) {
        this(id, clientId, title, description, budget, outputSpec, category, status,
                agentVersionId, result, createdAt, null, null, null, null);
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
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, category.trim(), TaskStatus.SUBMITTED, null, null, Instant.now());
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

    /** SUBMITTED/AWAITING_CAPACITY → QUEUED: a matching agent version was selected (re-match included). */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatusIn("assignAndQueue", TaskStatus.SUBMITTED, TaskStatus.AWAITING_CAPACITY);
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, agentVersionId, this.result);
    }

    /** QUEUED → EXECUTING: the task was dispatched to the agent. */
    public TaskModel markExecuting() {
        requireStatus(TaskStatus.QUEUED, "markExecuting");
        return copyWith(TaskStatus.EXECUTING, this.agentVersionId, this.result);
    }

    /** EXECUTING → RESULT_RECEIVED: the agent posted a result back. */
    public TaskModel recordResult(TaskResultModel result) {
        requireStatus(TaskStatus.EXECUTING, "recordResult");
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
        return copyWith(TaskStatus.SPEC_VIOLATION, this.agentVersionId, this.result);
    }

    /** SUBMITTED → AWAITING_CAPACITY (idempotent from AWAITING_CAPACITY for re-match no-match passes). */
    public TaskModel markAwaitingCapacity() {
        requireStatusIn("markAwaitingCapacity", TaskStatus.SUBMITTED, TaskStatus.AWAITING_CAPACITY);
        return copyWith(TaskStatus.AWAITING_CAPACITY, this.agentVersionId, this.result);
    }

    /** AWAITING_CAPACITY → CANCELLED: re-match attempts exhausted; the caller refunds escrow. */
    public TaskModel markCancelled() {
        requireStatus(TaskStatus.AWAITING_CAPACITY, "markCancelled");
        return copyWith(TaskStatus.CANCELLED, this.agentVersionId, this.result);
    }

    /** QUEUED/EXECUTING → TIMED_OUT: the agent did not respond in time. */
    public TaskModel markTimedOut() {
        requireStatusIn("markTimedOut", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return copyWith(TaskStatus.TIMED_OUT, this.agentVersionId, this.result);
    }

    /** QUEUED/EXECUTING → FAILED: dispatch/execution failed terminally. */
    public TaskModel markFailed() {
        requireStatusIn("markFailed", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return copyWith(TaskStatus.FAILED, this.agentVersionId, this.result);
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
                this.resolution, this.resolvedAt, trimReason(reason), reasonCategory);
    }

    /** DISPUTED → RESOLVED: the arbitration ruling (or fallback) has been settled. */
    public TaskModel resolveDispute(TaskResolution resolution) {
        requireStatus(TaskStatus.DISPUTED, "resolveDispute");
        requirePresent(resolution, "resolution");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                resolution, Instant.now(), this.rejectionReason, this.rejectReasonCategory);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): client changed their mind (D); charged in full, no dispute. */
    public TaskModel chargeChangedMind(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "chargeChangedMind");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                TaskResolution.REJECTED, Instant.now(), trimReason(reason), RejectReason.D_CHANGED_MIND);
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
                this.createdAt, resolution, Instant.now(), rejectionReason, this.rejectReasonCategory);
    }

    private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, newStatus, newAgentVersionId, newResult, this.createdAt,
                this.resolution, this.resolvedAt, this.rejectionReason, this.rejectReasonCategory);
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
}
