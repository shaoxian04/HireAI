package com.hireai.domain.biz.offering.embedding.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One Agent's profile vector, tagged with the embedding model that produced it and the hash of
 * the {@link AgentProfileText} it was computed from. Vectors from different models are never
 * comparable, so the model travels with the vector. At most one per Agent (replaced on re-embed).
 */
public record AgentEmbeddingModel(UUID agentId, String model, String textHash, float[] vector, Instant updatedAt) {

    /** Fixed by the {@code agent_embeddings.embedding vector(1536)} column. */
    public static final int DIMENSIONS = 1536;

    public AgentEmbeddingModel {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(textHash, "textHash");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (model == null || model.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Embedding model is required");
        }
        if (vector == null || vector.length != DIMENSIONS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Embedding must have " + DIMENSIONS + " dimensions, got "
                            + (vector == null ? "none" : vector.length));
        }
        vector = vector.clone();
    }

    public static AgentEmbeddingModel of(UUID agentId, String model, AgentProfileText text, float[] vector,
                                         Instant now) {
        return new AgentEmbeddingModel(agentId, model, text.hash(), vector, now);
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
