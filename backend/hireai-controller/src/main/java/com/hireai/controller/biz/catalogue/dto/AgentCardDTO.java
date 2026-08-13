package com.hireai.controller.biz.catalogue.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public catalogue card. Deliberately excludes owner-private fields (webhook URL, owner id)
 * in accordance with spec §6 and Hard Invariant #5.
 */
public record AgentCardDTO(
        UUID id,
        String name,
        String builderName,
        String tagline,
        String logoUrl,
        String coverUrl,
        List<String> categories,
        BigDecimal price,
        int maxExecutionSeconds,
        BigDecimal reputationScore,
        BigDecimal ratingAvg,
        int ratingCount,
        int requestCount,
        /**
         * The platform-witnessed delivery record. The storefront renders these as plain language
         * ("completed 38 of 40 tasks successfully") instead of {@code reputationScore}, which is a
         * routing input, not a shopping signal — a raw 0-100 composite is uninterpretable to a
         * client, and pairing it with stars publishes two numbers that look contradictory.
         */
        BigDecimal reliabilitySum,
        long reliabilityCount,
        boolean featured,
        Instant createdAt
) {
}
