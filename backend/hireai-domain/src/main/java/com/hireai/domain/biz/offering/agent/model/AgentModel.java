package com.hireai.domain.biz.offering.agent.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agent aggregate root. An Agent is a registered third-party executor owned by a Builder.
 * It owns a single {@link AgentVersionModel} (v1 in this slice) carrying the routable
 * contract. Behaviour lives here, not in setters; transitions are immutable (each returns
 * a new copy). Only ACTIVE agents are routable. Default reputation is 50.00.
 */
public final class AgentModel {

    public static final BigDecimal DEFAULT_REPUTATION = new BigDecimal("50.00");

    private final UUID id;
    private final UUID ownerId;
    private final String name;
    private final AgentStatus status;
    private final UUID currentVersionId;
    private final BigDecimal reputationScore;
    private final AgentVersionModel currentVersion;
    private final Instant createdAt;

    public AgentModel(UUID id, UUID ownerId, String name, AgentStatus status, UUID currentVersionId,
                      BigDecimal reputationScore, AgentVersionModel currentVersion, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.status = status;
        this.currentVersionId = currentVersionId;
        this.reputationScore = reputationScore;
        this.currentVersion = currentVersion;
        this.createdAt = createdAt;
    }

    /** Factory for the REGISTER transition: builds a PENDING_VERIFICATION agent + version v1. */
    public static AgentModel register(UUID ownerId, String name, OutputSpec outputSpec,
                                      List<String> capabilityCategories, String webhookUrl,
                                      int maxExecutionSeconds, Pricing pricing) {
        requirePresent(ownerId, "owner id");
        requireText(name, "name");
        UUID agentId = UUID.randomUUID();
        AgentVersionModel version = AgentVersionModel.create(agentId, 1, outputSpec,
                capabilityCategories, webhookUrl, maxExecutionSeconds, pricing);
        return new AgentModel(agentId, ownerId, name.trim(), AgentStatus.PENDING_VERIFICATION,
                null, DEFAULT_REPUTATION, version, Instant.now());
    }

    /** Immutable ACTIVATE transition: PENDING_VERIFICATION -> ACTIVE, sets current_version_id. */
    public AgentModel activate() {
        if (status != AgentStatus.PENDING_VERIFICATION) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only a PENDING_VERIFICATION agent can be activated; was " + status);
        }
        if (currentVersion == null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Agent has no version to activate");
        }
        return new AgentModel(id, ownerId, name, AgentStatus.ACTIVE, currentVersion.id(),
                reputationScore, currentVersion, createdAt);
    }

    /** Only an ACTIVE agent is routable / bookable. */
    public boolean isActive() {
        return status == AgentStatus.ACTIVE;
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

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String name() { return name; }
    public AgentStatus status() { return status; }
    public UUID currentVersionId() { return currentVersionId; }
    public BigDecimal reputationScore() { return reputationScore; }
    public AgentVersionModel currentVersion() { return currentVersion; }
    public Instant createdAt() { return createdAt; }
}
