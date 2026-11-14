package com.hireai.domain.biz.offering.embedding.info;

import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * A text's embedding tagged with the model that produced it. The candidate queries compare it only
 * with Agent vectors of the same model: vectors from different models are never compared.
 */
public record TextVector(String model, float[] vector) {

    public TextVector {
        if (model == null || model.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Embedding model is required");
        }
        if (vector == null || vector.length != AgentEmbeddingModel.DIMENSIONS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Embedding must have " + AgentEmbeddingModel.DIMENSIONS + " dimensions, got "
                            + (vector == null ? "none" : vector.length));
        }
        vector = vector.clone();
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }
}
