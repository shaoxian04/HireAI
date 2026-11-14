package com.hireai.domain.biz.task.info;

import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Carrier for a direct booking: the client targets ONE agent; the spec is adopted from it.
 * {@code category} is optional (null = the agent's first category); when given it must be a listed
 * slug the agent serves. {@code deadlineSeconds} is the optional Client deadline, counted from the
 * booking (null = none; #83): the agent must be able to finish within it.
 */
public record DirectBookingInfo(UUID clientId, String title, String description,
                                Money budget, UUID agentId, String category, Long deadlineSeconds) {

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId,
                             String category) {
        this(clientId, title, description, budget, agentId, category, null);
    }

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId) {
        this(clientId, title, description, budget, agentId, null);
    }
}
