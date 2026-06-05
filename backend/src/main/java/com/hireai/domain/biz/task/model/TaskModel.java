package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Task aggregate root. A task is the unit of work a client submits; its budget is
 * frozen in escrow at submission. Behaviour (the submit transition + its invariants)
 * lives here, not in setters. The aggregate is framework-free.
 */
public final class TaskModel {

    private final UUID id;
    private final UUID clientId;
    private final String title;
    private final String description;
    private final Money budget;
    private final OutputSpec outputSpec;
    private final TaskStatus status;
    private final Instant createdAt;

    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, TaskStatus status, Instant createdAt) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Factory for the SUBMIT transition: enforces invariants and creates a SUBMITTED task. */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec) {
        requirePresent(clientId, "client id");
        requireText(title, "title");
        requireText(description, "description");
        requirePositive(budget);
        requirePresent(outputSpec, "output spec");
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, TaskStatus.SUBMITTED, Instant.now());
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

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public String title() { return title; }
    public String description() { return description; }
    public Money budget() { return budget; }
    public OutputSpec outputSpec() { return outputSpec; }
    public TaskStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}
