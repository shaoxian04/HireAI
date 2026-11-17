package com.hireai.infrastructure.adjudication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.resource.InputStreamSource;
import com.networknt.schema.resource.SchemaLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON / JSON Schema 2020-12 inspection with networknt. Client-authored schemas are untrusted, so
 * this never fetches anything (Hard Invariant #6 in spirit: no request the platform makes can be
 * aimed by a schema):
 * <ul>
 *   <li>the factory's only loadable documents are the meta-schemas bundled on the classpath; a
 *       loader placed ahead of the defaults refuses every other IRI (http, https, file, ...);</li>
 *   <li>{@code $ref} and {@code $dynamicRef} must be local ({@code #...}), {@code $id} is refused
 *       (it would re-base references), and {@code $schema}, when present, must name 2020-12.
 *       External references are therefore refused outright, not merely left unresolved.</li>
 * </ul>
 * The submit-time {@link #problemWith} and the result-time {@link #check} use the same factory and
 * the same rules.
 */
@Component
public class NetworkntSchemaValidator implements SchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(NetworkntSchemaValidator.class);
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaLoaders(loaders -> loaders.values(list -> list.add(0, new BundledOnly()))));
    /** The 2020-12 meta-schema, loaded from the validator's bundled copy (no network). */
    private final JsonSchema metaSchema = schemaFactory.getSchema(SchemaLocation.of(SchemaId.V202012));

    @Override
    public JsonCheckResult check(String payloadJson, String schemaOrNull) {
        JsonNode payload;
        try {
            payload = payloadJson == null ? null : mapper.readTree(payloadJson);
        } catch (Exception e) {
            // The detail reaches the Client: the parser's own message ("Unrecognized token ...") stays in the log.
            log.debug("A result is not valid JSON: {}", e.getMessage());
            var at = e instanceof JsonProcessingException jpe ? jpe.getLocation() : null;
            return new JsonCheckResult(false, false, false, at == null || at.getLineNr() < 1
                    ? "the result isn't valid JSON"
                    : "the result isn't valid JSON (line " + at.getLineNr() + ", column " + at.getColumnNr() + ")");
        }
        // An empty or blank payload reads as a "missing" node, not as JSON: it is no result at all.
        if (payload == null || payload.isMissingNode()) {
            return new JsonCheckResult(false, false, false, "payload is empty, not JSON");
        }
        if (schemaOrNull == null || schemaOrNull.isBlank()) {
            return new JsonCheckResult(true, false, false, "valid JSON; no schema declared");
        }
        // A Contract never freezes a schema with a problem; only a Task from before Contracts can
        // reach here with one (prose, or a remote reference), and it is skipped as it always was.
        if (problemWith(schemaOrNull).isPresent()) {
            return new JsonCheckResult(true, false, false, "schema is not a usable JSON Schema; skipped");
        }
        Set<ValidationMessage> errors;
        try {
            errors = schemaFactory.getSchema(mapper.readTree(schemaOrNull)).validate(payload);
        } catch (Exception e) {
            log.warn("Checking a result against a schema failed; treating the schema as skipped", e);
            return new JsonCheckResult(true, false, false, "schema could not be applied; skipped");
        }
        return new JsonCheckResult(true, true, errors.isEmpty(),
                errors.isEmpty() ? "matches schema" : "doesn't match the schema: " + errors.stream().limit(5)
                        .map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
    }

    @Override
    public Optional<String> problemWith(String schema) {
        JsonNode node;
        try {
            node = mapper.readTree(schema);
        } catch (JsonProcessingException e) {
            // The parser's own message is noise to a person ("Unrecognized token ..."); the line and
            // column are what help them find the mistake.
            var at = e.getLocation();
            return Optional.of(at == null ? "it is not JSON"
                    : "it is not JSON (problem at line " + at.getLineNr() + ", column " + at.getColumnNr() + ")");
        }
        if (node == null || !node.isObject()) {
            return Optional.of("it is not a JSON object");
        }
        Optional<String> reference = nonLocalReference(node);
        if (reference.isPresent()) {
            return reference;
        }
        Set<ValidationMessage> broken = metaSchema.validate(node);
        if (!broken.isEmpty()) {
            return Optional.of("it breaks the JSON Schema 2020-12 rules: " + broken.stream()
                    .limit(3).map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
        }
        try {
            schemaFactory.getSchema(node);
        } catch (Exception e) {
            log.info("A client schema could not be compiled", e);
            return Optional.of("it could not be compiled");
        }
        return Optional.empty();
    }

    /**
     * Walks the whole document. A key named {@code $ref} etc. anywhere is treated as the keyword,
     * so a property literally named "$ref" with a non-local value is refused too (a rare, safe miss).
     */
    private static Optional<String> nonLocalReference(JsonNode node) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (("$ref".equals(key) || "$dynamicRef".equals(key))
                        && (!value.isTextual() || !value.asText().startsWith("#"))) {
                    return Optional.of("it refers to another document (" + key + "); only local "
                            + "references starting with # are allowed");
                }
                if ("$id".equals(key)) {
                    return Optional.of("it sets $id; schemas are checked as a single document without one");
                }
                if ("$schema".equals(key)
                        && (!value.isTextual() || !DRAFT_2020_12.equals(stripFragment(value.asText())))) {
                    return Optional.of("its $schema is not " + DRAFT_2020_12 + ", the only draft supported");
                }
                Optional<String> nested = nonLocalReference(value);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<String> nested = nonLocalReference(item);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    /** The factory itself, so a test can prove the loader refuses a fetch even past the scan. */
    JsonSchemaFactory factory() {
        return schemaFactory;
    }

    private static String stripFragment(String iri) {
        int hash = iri.indexOf('#');
        return hash < 0 ? iri : iri.substring(0, hash);
    }

    /**
     * Runs before networknt's default loaders: a bundled meta-schema ({@code classpath:}, after the
     * built-in json-schema.org mapping) falls through to the classpath loader; anything else is
     * refused before any URL or file loader can open it.
     */
    private static final class BundledOnly implements SchemaLoader {
        @Override
        public InputStreamSource getSchema(AbsoluteIri iri) {
            String value = iri == null ? "" : iri.toString();
            if (value.startsWith("classpath:")) {
                return null;
            }
            throw new IllegalStateException("Refusing to load schema document " + value);
        }
    }
}
