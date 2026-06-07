package com.hireai.controller.biz.catalogue.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full agent profile for the detail view. Nested records keep the type system flat and
 * avoid a proliferation of top-level DTO files. Owner-private fields are absent (spec §6).
 */
public record AgentProfileDTO(
        AgentCardDTO card,
        String description,
        String sampleOutput,
        List<String> galleryUrls,
        OutputSpecDTO outputSpec,
        StatsDTO stats,
        List<ReviewDTO> reviews
) {

    /** The binding output contract declared by the Agent builder (spec §4). */
    public record OutputSpecDTO(
            String format,
            String schema,
            String acceptanceCriteria
    ) {
    }

    /** Derived execution statistics computed from completed tasks. */
    public record StatsDTO(
            int requestCount,
            int completedCount,
            Double successRate,
            Double avgTurnaroundSeconds
    ) {
    }

    /** A published client review. Author is the username portion of the client's email. */
    public record ReviewDTO(
            UUID id,
            int rating,
            String reviewText,
            String builderResponse,
            String author,
            Instant createdAt
    ) {
    }
}
