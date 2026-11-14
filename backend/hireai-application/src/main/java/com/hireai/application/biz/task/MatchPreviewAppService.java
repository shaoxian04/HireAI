package com.hireai.application.biz.task;

import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read service for the frontend match preview: given a category + budget, returns an in-budget
 * shortlist (ranked by the domain matcher) and an above-budget near-miss list. No task is created
 * and no escrow is frozen — picking happens later via direct booking.
 *
 * <p>{@code excludedAgentIds} leaves those Agents (every version of them) out of both lists. The
 * web "Choose another agent" rebook passes the Agent that just failed the task (#79).
 *
 * <p>{@code deadlineSeconds} (optional, null = none) is the Client deadline counted from this request
 * (#83): an Agent that can't finish in time (now + max execution time + grace after it) is left out of
 * both lists.
 *
 * <p>{@code title} and {@code description} are the task text (#85): Agents whose profile is below the
 * relevance floor are left out of both lists, and the shortlist is ranked with relevance. When the text
 * can't be embedded the preview ranks without relevance. A vector made here is cached, so the booking
 * or routed submit that follows with the same text makes no second embedding call.
 */
public interface MatchPreviewAppService {

    MatchPreview preview(String title, String description, String category, Money budget,
                         Set<UUID> excludedAgentIds, Long deadlineSeconds);

    /**
     * {@code noCloseMatch}: the shortlist is empty although Agents in the category fit the budget (and
     * deadline), because every one of them is below the relevance floor. False when there simply is
     * no such Agent.
     */
    record MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses, boolean noCloseMatch) {
    }

    /**
     * {@code reliabilitySum}/{@code reliabilityCount} are the platform-witnessed delivery record.
     * The shortlist renders those rather than {@code reputationScore}, which is retained only as
     * the matcher's own input — it must never be drawn as if it were a client star rating.
     */
    record AgentOption(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                       String logoUrl, BigDecimal price, BigDecimal reputationScore, boolean available,
                       String outputFormat, List<String> capabilityCategories,
                       BigDecimal reliabilitySum, long reliabilityCount) {
    }
}
