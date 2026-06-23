package com.hireai.domain.biz.task.info;

import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/** Carrier for a direct booking: the client targets ONE agent; the spec is adopted from it. */
public record DirectBookingInfo(UUID clientId, String title, String description,
                                Money budget, UUID agentId) {
}
