package com.hireai.application.biz.acceptance.engagement;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;

import java.util.List;
import java.util.UUID;

/**
 * Engagements (D44, ADR 0010): a buyer's standing agreement with an Agent it chose, under which (and
 * only under which) confidential documents may be sent. The buyer opens and ends it; the Agent's Builder
 * accepts its Template variant. Every method checks the caller's side (Hard Invariant #5): a stranger
 * gets NOT_FOUND, never someone else's Engagement.
 */
public interface EngagementAppService {

    /**
     * Opens an Engagement with a bookable (ACTIVE, listed) Agent, recording the buyer's consent. A
     * {@code templateVariant} must be a valid Contract; the Engagement then waits for the Agent.
     */
    EngagementModel open(UUID clientId, UUID agentId, boolean confidentialConsent, Contract templateVariant,
                         Integer retentionDays);

    /** The Builder owning the Engagement's Agent accepts its Template variant. */
    EngagementModel acceptVariant(UUID engagementId, UUID builderId);

    /** The buyer ends its Engagement. */
    EngagementModel end(UUID engagementId, UUID clientId);

    /** One Engagement, for its buyer or for the Builder owning its Agent. */
    EngagementModel getForParticipant(UUID engagementId, UUID userId);

    /** The buyer's own Engagement (NOT_FOUND for anyone else). */
    EngagementModel getForClient(UUID engagementId, UUID clientId);

    /** The buyer's Engagements, newest first. */
    List<EngagementModel> listForClient(UUID clientId);

    /** The Engagements on Agents the Builder owns, newest first. */
    List<EngagementModel> listForBuilder(UUID builderId);
}
