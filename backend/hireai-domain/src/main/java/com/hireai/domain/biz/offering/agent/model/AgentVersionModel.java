package com.hireai.domain.biz.offering.agent.model;

import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Child entity of the {@link AgentModel} aggregate: one immutable, versioned snapshot of the
 * routable contract an Agent exposes — its output spec, its {@link Offer} (the Template versions and
 * parameter ranges it accepts, ADR 0009; its price is {@link #pricing()}), the capability categories
 * it serves, the HTTPS webhook, an execution-time ceiling, pricing, and a lifecycle {@link AgentVersionStatus}.
 * Persisted only through the root. The HTTPS rule enforces Hard Invariant #6.
 */
public final class AgentVersionModel {

    /** Upper bound on a version's declared execution time (24 hours). Mirrored by a DB CHECK. */
    public static final int MAX_EXECUTION_SECONDS = 86_400;

    private final UUID id;
    private final UUID agentId;
    private final int versionNumber;
    private final OutputSpec outputSpec;
    private final Offer offer;
    private final List<String> capabilityCategories;
    private final String webhookUrl;
    private final int maxExecutionSeconds;
    private final int maxConcurrent;
    private final Pricing pricing;
    private final AgentVersionStatus status;
    private final Instant createdAt;

    /** Rehydration constructor: every version carries an Offer (V39 backfilled the older ones). */
    public AgentVersionModel(UUID id, UUID agentId, int versionNumber, OutputSpec outputSpec, Offer offer,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, int maxConcurrent, Pricing pricing,
                             AgentVersionStatus status, Instant createdAt) {
        requirePresent(offer, "offer");
        this.id = id;
        this.agentId = agentId;
        this.versionNumber = versionNumber;
        this.outputSpec = outputSpec;
        this.offer = offer;
        this.capabilityCategories = List.copyOf(capabilityCategories);
        this.webhookUrl = webhookUrl;
        this.maxExecutionSeconds = maxExecutionSeconds;
        this.maxConcurrent = maxConcurrent;
        this.pricing = pricing;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Convenience constructor whose Offer is the output spec's equivalent (spec D39). */
    public AgentVersionModel(UUID id, UUID agentId, int versionNumber, OutputSpec outputSpec,
                             List<String> capabilityCategories, String webhookUrl,
                             int maxExecutionSeconds, int maxConcurrent, Pricing pricing,
                             AgentVersionStatus status, Instant createdAt) {
        this(id, agentId, versionNumber, outputSpec, outputSpec.toOwnOffer(), capabilityCategories, webhookUrl,
                maxExecutionSeconds, maxConcurrent, pricing, status, createdAt);
    }

    /**
     * Factory: validates the contract and builds a fresh ACTIVE version snapshot whose Offer is the
     * output spec's equivalent (spec D39). Its fit to the Templates is checked by the app layer.
     */
    public static AgentVersionModel create(UUID agentId, int versionNumber, OutputSpec outputSpec,
                                           List<String> capabilityCategories, String webhookUrl,
                                           int maxExecutionSeconds, Pricing pricing, int maxConcurrent) {
        requirePresent(agentId, "agent id");
        requirePresent(outputSpec, "output spec");
        requirePresent(pricing, "pricing");
        List<String> normalisedCategories = normaliseCategories(capabilityCategories);
        requireHttps(webhookUrl);
        requireValidMaxExecutionSeconds(maxExecutionSeconds);
        if (maxConcurrent < 1 || maxConcurrent > 100) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxConcurrent must be between 1 and 100");
        }
        return new AgentVersionModel(UUID.randomUUID(), agentId, versionNumber, outputSpec,
                outputSpec.toOwnOffer(), normalisedCategories, webhookUrl.trim(), maxExecutionSeconds, maxConcurrent, pricing,
                AgentVersionStatus.ACTIVE, Instant.now());
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

    private static void requireValidMaxExecutionSeconds(int maxExecutionSeconds) {
        if (maxExecutionSeconds <= 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "maxExecutionSeconds must be positive");
        }
        if (maxExecutionSeconds > MAX_EXECUTION_SECONDS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "maxExecutionSeconds must be at most " + MAX_EXECUTION_SECONDS + " (24 hours)");
        }
    }

    private static void requireHttps(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL is required");
        }
        if (!webhookUrl.trim().toLowerCase().startsWith("https://")) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Webhook URL must be HTTPS");
        }
    }

    /**
     * Supersession: produce the NEXT version (versionNumber + 1, fresh id + createdAt, status
     * ACTIVE) carrying over this version's immutable contract (outputSpec, offer, webhookUrl) with new
     * commercials. The CALLER demotes this version to DEPRECATED in the same transaction. Replaces
     * the old in-place updateCommercials mutation: in-flight tasks keep referencing the prior (now
     * DEPRECATED) version id, so no live contract is invalidated (Invariant #4).
     */
    public AgentVersionModel supersededBy(Pricing pricing, int maxExecutionSeconds,
                                          List<String> capabilityCategories) {
        requirePresent(pricing, "pricing");
        List<String> normalised = normaliseCategories(capabilityCategories);
        requireValidMaxExecutionSeconds(maxExecutionSeconds);
        return new AgentVersionModel(UUID.randomUUID(), agentId, versionNumber + 1, outputSpec, offer,
                normalised, webhookUrl, maxExecutionSeconds, maxConcurrent, pricing,
                AgentVersionStatus.ACTIVE, Instant.now());
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    /**
     * The category a direct booking of this version is filed under: the Client's choice when given,
     * which this version must serve, else its first capability category.
     */
    public String taskCategoryFor(String requested) {
        if (requested == null) {
            // Safe: create guarantees >= 1 category.
            return capabilityCategories.get(0);
        }
        if (!capabilityCategories.contains(requested)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "This agent does not serve category '" + requested + "'. It serves: "
                            + String.join(", ", capabilityCategories));
        }
        return requested;
    }

    /** Pricing rule: a task budget must cover this version's price. */
    public void assertAffordable(Money budget) {
        if (budget.value().compareTo(pricing.price()) < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Budget " + budget + " is below the agent's price " + pricing.price());
        }
    }

    public UUID id() { return id; }
    public UUID agentId() { return agentId; }
    public int versionNumber() { return versionNumber; }
    public OutputSpec outputSpec() { return outputSpec; }
    public Offer offer() { return offer; }
    public List<String> capabilityCategories() { return capabilityCategories; }
    public String webhookUrl() { return webhookUrl; }
    public int maxExecutionSeconds() { return maxExecutionSeconds; }
    public int maxConcurrent() { return maxConcurrent; }
    public Pricing pricing() { return pricing; }
    public AgentVersionStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}
