package com.hireai.controller.biz.task.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One selectable agent option in a match preview. availability is "AVAILABLE" or "BUSY". */
public record AgentOptionDTO(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                             String logoUrl, BigDecimal price, BigDecimal reputationScore,
                             String availability, String outputFormat, List<String> capabilityCategories) {
}
