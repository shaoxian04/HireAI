package com.hireai.domain.biz.acceptance.engagement.enums;

/**
 * An Engagement's lifecycle (D44). One with a Template variant waits for the Agent's acceptance of it;
 * one without starts ACTIVE. Only an ACTIVE Engagement takes uploads and bookings. ENDED is final.
 */
public enum EngagementStatus {
    AWAITING_AGENT_ACCEPTANCE,
    ACTIVE,
    ENDED
}
