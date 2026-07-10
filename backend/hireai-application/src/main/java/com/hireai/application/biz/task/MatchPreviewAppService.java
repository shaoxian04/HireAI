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

    record AgentOption(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                       String logoUrl, BigDecimal price, BigDecimal reputationScore, boolean available,
                       String outputFormat, List<String> capabilityCategories) {
    }
}
