package com.hireai.domain.biz.task.info;

import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Carrier for a direct booking: the client targets ONE agent; the spec is adopted from it.
 * {@code category} is optional (null = the agent's first category); when given it must be a listed
 * slug the agent serves.
 */
public record DirectBookingInfo(UUID clientId, String title, String description,
                                Money budget, UUID agentId, String category) {

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId) {
        this(clientId, title, description, budget, agentId, null);
    }
}
