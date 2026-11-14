package com.hireai.application.port.embedding;

/**
 * Turns text into an embedding vector with one fixed model. Implemented by the OpenAI client in
 * infrastructure. Callers must treat the provider as optional: matching and Builder edits never
 * depend on it being up.
 */
public interface EmbeddingPort {

    /** The model every vector from {@link #embed} comes from; stored next to the vector. */
    String model();

    /**
     * The vector for {@code text}.
     *
     * @throws EmbeddingUnavailableException when the provider can't answer (not configured, down,
     *         slow past the timeout, or an unusable response)
     */
    float[] embed(String text);
}
