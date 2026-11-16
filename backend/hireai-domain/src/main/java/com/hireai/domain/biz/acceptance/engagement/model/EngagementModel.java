package com.hireai.domain.biz.acceptance.engagement.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.engagement.enums.EngagementStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * A standing agreement between a buyer (a Client) and an Agent the buyer chose (D44, ADR 0010). It
 * records the buyer's consent to send that Agent confidential documents, which only an Engagement
 * allows; the marketplace paths stay public-only. It may carry a Template variant (a Contract the buyer
 * proposes), which the Agent must accept before the Engagement is ACTIVE; once accepted, a booking under
 * that exact Contract needs no Offer coverage, because the Agent agreed to it explicitly (D1).
 *
 * <p>{@code retentionDays} is how long the Engagement's SourceSnapshots keep their content after the
 * last window of the last Task using them closes (D32): 30 by default, a buyer may contract fewer.
 * Immutable: every transition returns a new copy and an illegal one throws.
 */
public record EngagementModel(UUID id, UUID clientId, UUID agentId, EngagementStatus status,
                              Instant consentedAt, Contract templateVariant, Instant agentAcceptedAt,
                              int retentionDays, Instant createdAt, Instant endedAt) {

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int MIN_RETENTION_DAYS = 1;

    /**
     * Opens an Engagement. The buyer's consent to send confidential documents is the reason an
     * Engagement exists, so it is required. A Template variant makes the Engagement wait for the Agent.
     */
    public static EngagementModel open(UUID clientId, UUID agentId, boolean confidentialConsent,
                                       Contract templateVariant, Integer retentionDays, Instant now) {
        if (!confidentialConsent) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "confidentialConsent: an Engagement records "
                    + "your consent to send this Agent confidential documents; it must be true");
        }
        int days = retentionDays == null ? DEFAULT_RETENTION_DAYS : retentionDays;
        if (days < MIN_RETENTION_DAYS || days > DEFAULT_RETENTION_DAYS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "retentionDays: must be between "
                    + MIN_RETENTION_DAYS + " and " + DEFAULT_RETENTION_DAYS + "; got " + days);
        }
        EngagementStatus status = templateVariant == null
                ? EngagementStatus.ACTIVE : EngagementStatus.AWAITING_AGENT_ACCEPTANCE;
        return new EngagementModel(UUID.randomUUID(), clientId, agentId, status, now, templateVariant, null,
                days, now, null);
    }

    /** AWAITING_AGENT_ACCEPTANCE → ACTIVE: the Agent's Builder accepts the Template variant. */
    public EngagementModel acceptVariant(Instant now) {
        if (status != EngagementStatus.AWAITING_AGENT_ACCEPTANCE) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "This Engagement has no Template variant waiting for acceptance (it is " + status + ")");
        }
        return new EngagementModel(id, clientId, agentId, EngagementStatus.ACTIVE, consentedAt, templateVariant,
                now, retentionDays, createdAt, null);
    }

    /** Any → ENDED: the buyer ends it. No new uploads or bookings; Tasks already booked run on. */
    public EngagementModel end(Instant now) {
        if (status == EngagementStatus.ENDED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "This Engagement has already ended");
        }
        return new EngagementModel(id, clientId, agentId, EngagementStatus.ENDED, consentedAt, templateVariant,
                agentAcceptedAt, retentionDays, createdAt, now);
    }

    /** Refuses (409) an upload or a booking unless the Engagement is ACTIVE. */
    public void requireActive() {
        if (status != EngagementStatus.ACTIVE) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, status == EngagementStatus.ENDED
                    ? "This Engagement has ended"
                    : "This Engagement is waiting for the Agent to accept its Template variant");
        }
    }

    /** Refuses (400) a booking under this Engagement with another Agent. */
    public void requireAgent(UUID bookedAgentId) {
        if (!agentId.equals(bookedAgentId)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "engagementId: this Engagement is with another Agent");
        }
    }

    public boolean isOwnedBy(UUID userId) {
        return clientId.equals(userId);
    }

    /**
     * Whether the Agent explicitly accepted exactly {@code contract} as this Engagement's Template
     * variant, so a booking under it needs no Offer coverage.
     */
    public boolean agentAccepted(Contract contract) {
        return templateVariant != null && agentAcceptedAt != null && contract != null
                && sameTerms(templateVariant, contract);
    }

    /** Same Template version and parameters; numbers compare by value (10 and 10.0 are the same term). */
    private static boolean sameTerms(Contract a, Contract b) {
        if (!a.template().equals(b.template()) || !a.params().keySet().equals(b.params().keySet())) {
            return false;
        }
        return a.params().entrySet().stream().allMatch(e -> {
            ParameterValue other = b.params().get(e.getKey());
            if (e.getValue() instanceof ParameterValue.Number n && other instanceof ParameterValue.Number m) {
                return n.value().compareTo(m.value()) == 0;
            }
            return e.getValue().equals(other);
        });
    }
}
