package com.hireai.controller.biz.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Outbound HTTP DTO for an agent (with its current version). No domain types leak.
 * {@code hasSigningSecret} says whether an AgentSigningSecret is set; the secret itself is never
 * part of this DTO (it is returned only by registration and rotation). {@code listed} says whether the
 * storefront is listed: an unlisted Agent is never matched, so it receives no work.
 */
public record AgentDTO(
        UUID id,
        UUID ownerId,
        String name,
        String status,
        UUID currentVersionId,
        BigDecimal reputationScore,
        AgentVersionDTO currentVersion,
        Instant createdAt,
        boolean hasSigningSecret,
        boolean listed
) {

    public record AgentVersionDTO(
            UUID id,
            int versionNumber,
            OutputSpecDTO outputSpec,
            List<String> capabilityCategories,
            String webhookUrl,
            int maxExecutionSeconds,
            BigDecimal price,
            String status
    ) {
    }

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
