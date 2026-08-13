package com.hireai.application.biz.task;

import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read service for the frontend match preview: given a category + budget, returns an in-budget
 * shortlist (ranked by the domain matcher) and an above-budget near-miss list. No task is created
 * and no escrow is frozen — picking happens later via direct booking.
 */
public interface MatchPreviewAppService {

    MatchPreview preview(String category, Money budget);

    record MatchPreview(List<AgentOption> shortlist, List<AgentOption> nearMisses) {
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
