package com.hireai.application.biz.offering.embedding;

import com.hireai.domain.biz.offering.embedding.info.TextVector;

import java.util.Optional;

/**
 * The task text's vector for relevance matching (#85), from the {@code text_embeddings} cache when
 * this title + description was already embedded with the configured model. Matching never depends on
 * the provider: an empty result means "rank without relevance".
 */
public interface TaskTextEmbeddingAppService {

    /** Cached vector, else one from the provider (then cached); empty if the provider can't answer. */
    Optional<TextVector> vectorFor(String title, String description);

    /**
     * Cached vector only, never a provider call: for a selection made while holding a row lock
     * (a failover hop), which must not wait on the provider.
     */
    Optional<TextVector> cachedVectorFor(String title, String description);
}
