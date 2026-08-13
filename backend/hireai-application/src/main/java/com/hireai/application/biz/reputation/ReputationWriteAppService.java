package com.hireai.application.biz.reputation;

import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.info.ReputationScore;

import java.util.UUID;

/**
 * Records terminal outcomes against an agent's standing and keeps the derived score current.
 *
 * <p><strong>Emission is explicit at each transition site.</strong> There is no single choke point,
 * and the absence of one is a finding rather than an oversight: the same settlement call maps to
 * three different outcomes. An acceptance and a won dispute settle identically but are different
 * events, while a changed-mind rejection settles identically again and must emit nothing at all.
 * The task's own resolution field is equally unusable — {@code chargeChangedMind} records the
 * resolution as REJECTED while paying the builder in full, so a rule keyed off it gets that case
 * backwards in both directions.
 *
 * <p>Every method is a no-op when the task's client owns the agent (the L1 self-dealing
 * exclusion), so callers do not each have to remember the check.
 */
public interface ReputationWriteAppService {

    /**
     * Records a platform-witnessed outcome for the agent that executed this task, and rescores it
     * in the same transaction — the score is never stale and there is no sweeper.
     *
     * @param type must be a RELIABILITY-component type; ratings go through
     *             {@link #recordRating}, which derives quality from stars
     */
    void recordOutcome(UUID taskId, UUID agentVersionId, UUID clientId, ReputationEventType type);

    /** Records a client rating (1–5 stars) against Satisfaction. */
    void recordRating(UUID taskId, UUID agentVersionId, UUID clientId, int stars);

    /**
     * Replays the agent's whole event stream, rewrites the cached aggregates from it, and returns
     * the recomputed score. The aggregates on the agents row are a derived cache; this is the path
     * that proves they still agree with the append-only source of truth (Invariant #2).
     */
    ReputationScore reconcile(UUID agentId);
}
