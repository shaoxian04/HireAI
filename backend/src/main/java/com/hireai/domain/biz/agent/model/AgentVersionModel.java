package com.hireai.domain.biz.agent.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Child entity of the {@link AgentModel} aggregate: one immutable, versioned snapshot of
 * the routable contract an Agent exposes — its output spec, the capability categories it
 * serves, the HTTPS webhook it is dispatched to, an execution-time ceiling, and pricing.
 * Persisted only through the root. The HTTPS rule enforces Hard Invariant #6.
 */
public final class AgentVersionModel {

    private final UUID id;
    private final UUID agentId;
    private final int versionNumber;
    private final OutputSpec outputSpec;
    private final List<String> capabilityCategories;
    private final String webhookUrl;
    private final int maxExecutionSeconds;
    private final Pricing pricing;
    private final Instant createdAt;

    public AgentVersionModel(UUID id, UUID agentId, int versionNumber, OutputSpec outputSpec,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, Pricing pricing, Instant createdAt) {
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.capabilityCategories = List.copyOf(capabilityCategories);
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.pricing = pricing;
        this.createdAt = createdAt;
    }

    /** Factory: validates the contract and builds a fresh version snapshot. */
    public static AgentVersionModel create(UUID agentId, int versionNumber, OutputSpec outputSpec,
                                           List<String> capabilityCategories, String webhookUrl,
                                           int maxExecutionSeconds, Pricing pricing) {
        requirePresent(agentId, "agent id");
        requirePresent(outputSpec, "output spec");
        requirePresent(pricing, "pricing");
        List<String> normalisedCategories = normaliseCategories(capabilityCategories);
        requireHttps(webhookUrl);
        if (maxExecutionSeconds <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxExecutionSeconds must be positive");
        }
        return new AgentVersionModel(UUID.randomUUID(), agentId, versionNumber, outputSpec,
                normalisedCategories, webhookUrl.trim(), maxExecutionSeconds, pricing, Instant.now());
    }

    private static List<String> normaliseCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "At least one capability category is required");
        }
        return categories.stream()
                .map(category -> {
                    if (category == null || category.isBlank()) {
                        throw new DomainException(ResultCode.VALIDATION_ERROR, "Capability category must not be blank");
                    }
                    return category.trim().toLowerCase();
                })
                .toList();
    }

    private static void requireHttps(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL is required");
        }
        if (!webhookUrl.trim().toLowerCase().startsWith("https://")) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL must be HTTPS");
        }
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    public UUID id() { return id; }
    public UUID agentId() { return agentId; }
    public int versionNumber() { return versionNumber; }
    public OutputSpec outputSpec() { return outputSpec; }
    public List<String> capabilityCategories() { return capabilityCategories; }
    public String webhookUrl() { return webhookUrl; }
    public int maxExecutionSeconds() { return maxExecutionSeconds; }
    public Pricing pricing() { return pricing; }
    public Instant createdAt() { return createdAt; }
}
