package com.hireai.application.biz.reputation;

import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * The builder-facing "why is my reputation what it is?" read.
 *
 * <p>Returns the two components <em>separately</em>, each with its sample count, plus the events
 * behind them. That separation is the payoff of the two-component model: a single blended number
 * cannot tell a builder whether their agent is <strong>failing to deliver</strong> or
 * <strong>delivering work clients are unimpressed by</strong>, and those demand completely
 * different fixes. The builder's income depends on telling them apart.
 *
 * <p>Enforces Invariant #5: only the owning builder may read a breakdown, and a foreign agent is
 * indistinguishable from a missing one.
 */
@Validated
public interface ReputationReadAppService {

    /** Default size of the recent-event window returned with a breakdown. */
    int DEFAULT_EVENT_LIMIT = 20;

    Breakdown getForOwner(@NonNull UUID agentId, @NonNull UUID ownerId);

    /**
     * @param score       both components, the blend, and the sample counts behind each
     * @param recentEvents newest first — so a drop can be traced to the specific tasks that caused it
     */
    record Breakdown(ReputationScore score, List<ReputationEventModel> recentEvents) {
    }
}
