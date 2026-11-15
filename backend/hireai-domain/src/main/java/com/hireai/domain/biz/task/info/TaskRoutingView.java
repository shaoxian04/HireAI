package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.model.ClientDeadline;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model projection of a Task carrying what routing needs to match it (category, budget,
 * current status) and to build the WorkOrder an Agent receives (title, description, and the
 * task's adopted output spec). Framework-free. Produced by {@code TaskReadAppService.getRoutingView}
 * and passed to the routing matcher; the routing trigger re-reads the task rather than enriching
 * the submitted event, so the event contract stays stable.
 *
 * <p>{@code outputSpecJson} is the JSON snapshot of the output_spec column on the task row.
 * It is the binding contract per Hard Invariant #4 — the spec is frozen at submit time and
 * must be used in the dispatch payload, not re-read from the agent version.
 *
 * <p>{@code clientDeadline} is the Client's deadline fixed at submit, or null when none was given
 * (#83); every selection for this task admits only Agents that fit it at that moment.
 *
 * <p>{@code contract} is the task's frozen Contract (or, for a task from before V39, its output spec's
 * equivalent): every selection admits only Agents whose Offer covers it (#91).
 */
public record TaskRoutingView(UUID taskId, String title, String description, String category,
                              BigDecimal budget, String status, String outputSpecJson,
                              ClientDeadline clientDeadline, Contract contract)
        implements MatchCriteria {

    /** A view without a Contract (only tests build one): routing then filters no Offer. */
    public TaskRoutingView(UUID taskId, String title, String description, String category,
                           BigDecimal budget, String status, String outputSpecJson,
                           ClientDeadline clientDeadline) {
        this(taskId, title, description, category, budget, status, outputSpecJson, clientDeadline, null);
    }

    /** A view of a task without a client deadline. */
    public TaskRoutingView(UUID taskId, String title, String description, String category,
                           BigDecimal budget, String status, String outputSpecJson) {
        this(taskId, title, description, category, budget, status, outputSpecJson, null);
    }
}
