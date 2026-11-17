package com.hireai.controller.biz.catalogue.dto;

import com.hireai.controller.biz.acceptance.dto.OfferDTO;
import com.hireai.controller.biz.task.dto.ContractDeclineRateDTO;

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
        /** The terms the Agent works under (ADR 0009); a direct booking freezes the matching Contract. */
        OfferDTO offer,
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

    /**
     * Derived execution statistics computed from completed tasks, plus the public contract-decline
     * rate (#93) from the dispatch history.
     */
    public record StatsDTO(
            int requestCount,
            int completedCount,
            Double successRate,
            Double avgTurnaroundSeconds,
            ContractDeclineRateDTO contractDeclines
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
