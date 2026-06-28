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
 * It owns the current routable version (ACTIVE); prior versions are retained as DEPRECATED
 * history. Behaviour lives here, not in setters; transitions are immutable (each returns
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

    /**
     * Publish-new-version (supersession): produce a copy whose currentVersion is the NEXT version
     * (status ACTIVE, versionNumber + 1), carrying over the current version's outputSpec + webhookUrl
     * with new commercials. The repository demotes the prior ACTIVE version to DEPRECATED in the same
     * transaction. current_version_id advances to the new version only when the agent is ACTIVE
     * (a PENDING_VERIFICATION agent keeps null until activation, mirroring register()).
     */
    public AgentModel publishNewVersion(Pricing pricing, int maxExecutionSeconds,
                                        List<String> capabilityCategories) {
        if (currentVersion == null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Agent has no current version to supersede");
        }
        AgentVersionModel next = currentVersion.supersededBy(pricing, maxExecutionSeconds, capabilityCategories);
        UUID newCurrentVersionId = status == AgentStatus.ACTIVE ? next.id() : currentVersionId;
        return new AgentModel(id, ownerId, name, status, newCurrentVersionId,
                reputationScore, next, createdAt);
    }

    /** ACTIVE -> SUSPENDED: pause routing/booking; reversible via reactivate(). */
    public AgentModel suspend() {
        if (status != AgentStatus.ACTIVE) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an ACTIVE agent can be suspended; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.SUSPENDED, currentVersionId,
                reputationScore, currentVersion, createdAt);
    }

    /** SUSPENDED -> ACTIVE: resume routing/booking. */
    public AgentModel reactivate() {
        if (status != AgentStatus.SUSPENDED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only a SUSPENDED agent can be reactivated; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.ACTIVE, currentVersionId,
                reputationScore, currentVersion, createdAt);
    }

    /** ACTIVE or SUSPENDED -> DEACTIVATED: terminal retirement (no return). */
    public AgentModel deactivate() {
        if (status != AgentStatus.ACTIVE && status != AgentStatus.SUSPENDED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an ACTIVE or SUSPENDED agent can be deactivated; was " + status);
        }
        return new AgentModel(id, ownerId, name, AgentStatus.DEACTIVATED, currentVersionId,
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
