package com.hireai.domain.biz.offering.category.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A Builder's free-text proposal for a category the list lacks, sent with a registration or a
 * published version and kept for admins. It never affects matching: the Agent still matches only
 * by the list slugs it chose (typically {@code other}).
 */
public record CategorySuggestion(UUID id, UUID agentId, UUID agentVersionId, UUID ownerId,
                                 String text, Instant createdAt) {

    public static final int MAX_LENGTH = 100;

    /** Empty when {@code raw} is null or blank: the field is optional. */
    public static Optional<CategorySuggestion> of(String raw, UUID agentId, UUID agentVersionId,
                                                  UUID ownerId, Instant now) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.length() > MAX_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "suggestedCategory must be at most " + MAX_LENGTH + " characters");
        }
        return Optional.of(new CategorySuggestion(UUID.randomUUID(), agentId, agentVersionId,
                ownerId, text, now));
    }
}
