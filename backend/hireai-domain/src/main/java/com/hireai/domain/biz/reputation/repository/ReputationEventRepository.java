package com.hireai.domain.biz.reputation.repository;

import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;

import java.util.List;
import java.util.UUID;

/**
 * Persistence contract for the append-only reputation event stream. There is deliberately no
 * update or delete: the database refuses both (Invariant #2), and offering them here would invite
 * a caller to try.
 */
public interface ReputationEventRepository {

    ReputationEventModel append(ReputationEventModel event);

    /** Recent events for an agent, newest first — the builder-facing "why is my score this?" read. */
    List<ReputationEventModel> findRecentByAgentId(UUID agentId, int limit);

    /**
     * Replays the whole stream for an agent and re-derives its aggregates from scratch. The
     * aggregates cached on the agents row are a derived cache; this is the path that proves they
     * agree with the source of truth.
     */
    ReputationAggregates replayAggregates(UUID agentId);

    /** True when this task already produced an event of this type — emission is exactly-once. */
    boolean existsByTaskIdAndEventType(UUID taskId, ReputationEventType eventType);
}
