package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
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

    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt) {
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

    /** SUBMITTED → QUEUED: a matching agent version was selected. */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatus(TaskStatus.SUBMITTED, "assignAndQueue");
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

    /** SUBMITTED → AWAITING_CAPACITY: no eligible active agent was found. */
    public TaskModel markAwaitingCapacity() {
        requireStatus(TaskStatus.SUBMITTED, "markAwaitingCapacity");
        return copyWith(TaskStatus.AWAITING_CAPACITY, this.agentVersionId, this.result);
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

    private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.category, newStatus, newAgentVersionId, newResult, this.createdAt);
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
}
