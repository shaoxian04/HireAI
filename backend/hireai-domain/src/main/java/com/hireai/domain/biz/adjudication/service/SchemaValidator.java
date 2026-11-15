package com.hireai.domain.biz.adjudication.service;

import org.jspecify.annotations.Nullable;

import java.util.Optional;

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

    /**
     * Why {@code schema} cannot be used to check results (it is not JSON, not a JSON object, or not
     * a valid JSON Schema), or empty when it can. A Contract never freezes a schema that has a
     * problem (spec D8), so {@link #check} never skips one it was given.
     */
    Optional<String> problemWith(String schema);
}
