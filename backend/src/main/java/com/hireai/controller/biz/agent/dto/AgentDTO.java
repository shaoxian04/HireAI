package com.hireai.controller.biz.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound HTTP DTO for an agent (with its current version). No domain types leak. */
public record AgentDTO(
        UUID id,
        UUID ownerId,
        String name,
        String status,
        UUID currentVersionId,
        BigDecimal reputationScore,
        AgentVersionDTO currentVersion,
        Instant createdAt
) {

    public record AgentVersionDTO(
            UUID id,
            int versionNumber,
            OutputSpecDTO outputSpec,
            List<String> capabilityCategories,
            String webhookUrl,
            int maxExecutionSeconds,
            BigDecimal price
    ) {
    }

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
