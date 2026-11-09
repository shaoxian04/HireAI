package com.hireai.domain.biz.offering.embedding.info;

import com.hireai.domain.biz.offering.embedding.model.AgentProfileText;

import java.util.UUID;

/**
 * An Agent's current profile fields next to the model and text hash of its stored vector (both
 * null when it has none). Decides whether the vector must be (re)computed.
 */
public record AgentEmbeddingSource(UUID agentId, String name, String tagline, String description,
                                   String storedModel, String storedTextHash) {

    public AgentProfileText profileText() {
        return AgentProfileText.of(name, tagline, description);
    }

    /** True when the stored vector is missing, from another model, or from older profile text. */
    public boolean needsEmbedding(String model) {
        return storedModel == null
                || !storedModel.equals(model)
                || !profileText().hash().equals(storedTextHash);
    }
}
