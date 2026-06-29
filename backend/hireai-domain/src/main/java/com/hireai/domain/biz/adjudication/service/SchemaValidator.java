package com.hireai.domain.biz.adjudication.service;

import org.jspecify.annotations.Nullable;

/**
 * Domain port for JSON / JSON-Schema inspection. Implemented in infrastructure so the domain
 * stays framework-free (no Jackson / networknt on the domain classpath).
 */
public interface SchemaValidator {

    /**
     * @param payloadJson the agent's result payload
     * @param schemaOrNull the task's declared output_spec.schema (may be null or free prose)
     */
    JsonCheckResult check(String payloadJson, @Nullable String schemaOrNull);
}
