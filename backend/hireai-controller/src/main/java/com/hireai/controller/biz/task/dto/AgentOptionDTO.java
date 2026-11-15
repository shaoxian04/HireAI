package com.hireai.controller.biz.task.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One selectable agent option in a match preview. availability is "AVAILABLE" or "BUSY".
 * {@code reliabilitySum}/{@code reliabilityCount} are the platform-witnessed delivery record the
 * shortlist renders; {@code contractDeclines} is the public contract-decline rate (#93).
 */
public record AgentOptionDTO(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                             String logoUrl, BigDecimal price, BigDecimal reputationScore,
                             String availability, String outputFormat, List<String> capabilityCategories,
                             BigDecimal reliabilitySum, long reliabilityCount,
                             ContractDeclineRateDTO contractDeclines) {
}
