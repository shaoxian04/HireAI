package com.hireai.application.port.query;

import com.hireai.domain.biz.offering.embedding.info.TextVector;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-side port for the frontend match-preview. Returns every BOOKABLE (ACTIVE + listed)
 * agent whose current version covers the category — with NO price filter, so the app service
 * can split the result into the in-budget shortlist and the above-budget near-miss list. Rows
 * carry both display fields and the scorer's per-agent metrics.
 */
public interface MatchPreviewQueryPort {

    /**
     * {@code longestExecutionSeconds}: the largest max execution time that fits the Client's deadline
     * at this moment ({@code ClientDeadline.longestExecutionSeconds}); null = no deadline. A slower
     * Agent is left out of both lists. {@code taskVector} (null = unavailable) gives each row its raw
     * cosine {@code similarity} to an Agent vector of the same model (else null).
     */
    List<ShortlistCandidateRow> findBookableCandidates(String category, Long longestExecutionSeconds,
                                                       TextVector taskVector);

    /** {@link #findBookableCandidates(String, Long, TextVector)} without a task vector. */
    default List<ShortlistCandidateRow> findBookableCandidates(String category, Long longestExecutionSeconds) {
        return findBookableCandidates(category, longestExecutionSeconds, null);
    }

    /** {@link #findBookableCandidates(String, Long)} without a client deadline. */
    default List<ShortlistCandidateRow> findBookableCandidates(String category) {
        return findBookableCandidates(category, null, null);
    }

    record ShortlistCandidateRow(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                                 String logoUrl, BigDecimal price, BigDecimal reputationScore,
                                 BigDecimal reliabilitySum, long reliabilityCount,
                                 List<String> capabilityCategories, String outputSpecJson,
                                 String outputFormat, String webhookUrl, int maxExecutionSeconds,
                                 int maxConcurrent, long inFlight, long sampleCount, Double similarity) {

        /** A row without a similarity. */
        public ShortlistCandidateRow(UUID agentId, UUID agentVersionId, String agentName, String tagline,
                                     String logoUrl, BigDecimal price, BigDecimal reputationScore,
                                     BigDecimal reliabilitySum, long reliabilityCount,
                                     List<String> capabilityCategories, String outputSpecJson,
                                     String outputFormat, String webhookUrl, int maxExecutionSeconds,
                                     int maxConcurrent, long inFlight, long sampleCount) {
            this(agentId, agentVersionId, agentName, tagline, logoUrl, price, reputationScore, reliabilitySum,
                    reliabilityCount, capabilityCategories, outputSpecJson, outputFormat, webhookUrl,
                    maxExecutionSeconds, maxConcurrent, inFlight, sampleCount, null);
        }
    }
}
