package com.hireai.application.biz.task;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.domain.biz.task.routing.info.NoMatchExplanation;
import com.hireai.domain.biz.task.dispatchattempt.info.ContractDeclineRate;
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
 *
 * <p>{@code contract} (optional, null = none) is the Contract the Client wants to book under (#91): only
 * Agents whose Offer covers it are listed, near-misses included. It must be valid for its Template
 * (VALIDATION_ERROR otherwise). {@code noMatch} explains an empty shortlist (spec D35): which parameter,
 * Template version or the relevance floor excluded the in-budget Agents, with relax suggestions.
 */
public interface MatchPreviewAppService {

    MatchPreview preview(String title, String description, String category, Money budget,
                         Set<UUID> excludedAgentIds, Long deadlineSeconds, Contract contract);

    /** A preview that states no Contract: no Offer coverage filter. */
    default MatchPreview preview(String title, String description, String category, Money budget,
                                 Set<UUID> excludedAgentIds, Long deadlineSeconds) {
        return preview(title, description, category, budget, excludedAgentIds, deadlineSeconds, null);
    }

    /**
     * {@code noCloseMatch}: the shortlist is empty although Agents in the category fit the budget (and
     * deadline), because every one of them is below the relevance floor. False when there simply is
     * no such Agent. {@code fallback} (spec D36): the preview's Contract was matched under the previous
     * Template version because no Agent's Offer covers the current one yet; null otherwise.
     */
    record MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses, boolean noCloseMatch,
                        NoMatchExplanation noMatch, VersionFallback fallback) {

        public MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses, boolean noCloseMatch,
                            NoMatchExplanation noMatch) {
            this(shortlist, nearMisses, noCloseMatch, noMatch, null);
        }

        public MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses, boolean noCloseMatch) {
            this(shortlist, nearMisses, noCloseMatch, null);
        }
    }

    /**
     * {@code reliabilitySum}/{@code reliabilityCount} are the platform-witnessed delivery record.
     * The shortlist renders those rather than {@code reputationScore}, which is retained only as
     * the matcher's own input — it must never be drawn as if it were a client star rating.
     * {@code contractDeclines} is the Agent's public contract-decline rate (#93); shown, never ranked on.
     */
    record AgentOption(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                       String logoUrl, BigDecimal price, BigDecimal reputationScore, boolean available,
                       String outputFormat, List<String> capabilityCategories,
                       BigDecimal reliabilitySum, long reliabilityCount, ContractDeclineRate contractDeclines) {

        public AgentOption withContractDeclines(ContractDeclineRate rate) {
            return new AgentOption(agentId, agentVersionId, agentName, tagline, logoUrl, price, reputationScore,
                    available, outputFormat, capabilityCategories, reliabilitySum, reliabilityCount, rate);
        }
    }
}
