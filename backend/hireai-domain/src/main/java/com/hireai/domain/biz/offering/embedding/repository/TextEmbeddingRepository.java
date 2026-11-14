package com.hireai.domain.biz.offering.embedding.repository;

import com.hireai.domain.biz.offering.embedding.info.TextVector;

import java.time.Instant;
import java.util.Optional;

/** The {@code text_embeddings} cache: one vector per (text hash, model). The text itself isn't stored. */
public interface TextEmbeddingRepository {

    Optional<TextVector> find(String textHash, String model);

    /** Stores the vector unless that (text hash, model) is already cached (a concurrent writer won). */
    void saveIfAbsent(String textHash, TextVector vector, Instant now);
}
