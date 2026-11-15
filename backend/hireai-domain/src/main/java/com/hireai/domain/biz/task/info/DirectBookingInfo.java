package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.shared.model.Money;

import java.util.List;
import java.util.UUID;

/**
 * Carrier for a direct booking: the client targets ONE agent; the spec is adopted from it.
 * {@code category} is optional (null = the agent's first category); when given it must be a listed
 * slug the agent serves. {@code deadlineSeconds} is the optional Client deadline, counted from the
 * booking (null = none; #83): the agent must be able to finish within it. {@code contract} is the
 * optional Contract the Client books under (#91): the agent's Offer must cover it, and it is frozen on
 * the task instead of the agent's own spec's equivalent (null = that equivalent).
 *
 * <p>{@code items} are the inputs of a multi-Item Task (spec S3), empty for a single-deliverable
 * booking. With Items, {@code budget} is the per-Item price (it must cover the Agent's price) and the
 * Task freezes {@link #escrow()}.
 */
public record DirectBookingInfo(UUID clientId, String title, String description,
                                Money budget, UUID agentId, String category, Long deadlineSeconds,
                                Contract contract, List<String> items) {

    public DirectBookingInfo {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId,
                             String category, Long deadlineSeconds, Contract contract) {
        this(clientId, title, description, budget, agentId, category, deadlineSeconds, contract, List.of());
    }

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId,
                             String category, Long deadlineSeconds) {
        this(clientId, title, description, budget, agentId, category, deadlineSeconds, null, List.of());
    }

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId,
                             String category) {
        this(clientId, title, description, budget, agentId, category, null);
    }

    public DirectBookingInfo(UUID clientId, String title, String description, Money budget, UUID agentId) {
        this(clientId, title, description, budget, agentId, null);
    }

    /** What the Task freezes: the budget, or the per-Item price × the Item count. */
    public Money escrow() {
        return items.isEmpty() ? budget : TaskItems.escrowFor(budget, items.size());
    }
}
