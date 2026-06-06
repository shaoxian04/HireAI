package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port for the public catalogue (CQRS read over agents/profiles/reviews/tasks).
 * Only ACTIVE + listed agents are ever returned. Implementations own the SQL; sort keys are
 * whitelisted here so user input never reaches ORDER BY.
 */
public interface CatalogueQueryPort {

    List<String> SORT_KEYS = List.of("hot", "rating", "price_asc", "price_desc", "newest");

    List<AgentCardRow> searchCards(String q, String category, String sort, int page, int size);

    Optional<AgentProfileRow> findProfile(UUID agentId);

    List<CategoryCountRow> categoryCounts();

    List<ReviewRow> reviewsForAgent(UUID agentId, int limit);

    record AgentCardRow(UUID id, String name, String builderName, BigDecimal reputationScore,
                        String tagline, String logoUrl, String coverUrl, boolean featured,
                        List<String> categories, BigDecimal price, int maxExecutionSeconds,
                        BigDecimal ratingAvg, int ratingCount, int requestCount, Instant createdAt) {
    }

    record AgentProfileRow(AgentCardRow card, String description, String sampleOutput,
                           List<String> galleryUrls, String outputSpecJson,
                           int completedCount, Double avgTurnaroundSeconds) {
    }

    record CategoryCountRow(String category, int agentCount) {
    }

    record ReviewRow(UUID id, int rating, String reviewText, String builderResponse,
                     String author, Instant createdAt) {
    }
}
