package com.hireai.infrastructure.adjudication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class NetworkntSchemaValidator implements SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    /** The 2020-12 meta-schema, loaded from the validator's bundled copy (no network). */
    private final JsonSchema metaSchema = schemaFactory.getSchema(SchemaLocation.of(SchemaId.V202012));

    @Override
    public JsonCheckResult check(String payloadJson, String schemaOrNull) {
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson);
        } catch (Exception e) {
            return new JsonCheckResult(false, false, false, "payload is not valid JSON: " + e.getMessage());
        }
        if (schemaOrNull == null || schemaOrNull.isBlank()) {
            return new JsonCheckResult(true, false, false, "valid JSON; no schema declared");
        }
        JsonSchema schema;
        try {
            JsonNode schemaNode = mapper.readTree(schemaOrNull);
            if (!schemaNode.isObject()) {
                return new JsonCheckResult(true, false, false, "schema is not a JSON Schema object; skipped");
            }
            schema = schemaFactory.getSchema(schemaNode);
        } catch (Exception e) {
            return new JsonCheckResult(true, false, false, "schema is free prose, not a JSON Schema; skipped");
        }
        Set<ValidationMessage> errors = schema.validate(payload);
        return new JsonCheckResult(true, true, errors.isEmpty(),
                errors.isEmpty() ? "matches schema" : errors.toString());
    }

    @Override
    public Optional<String> problemWith(String schema) {
        JsonNode node;
        try {
            node = mapper.readTree(schema);
        } catch (JsonProcessingException e) {
            return Optional.of("it is not JSON: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            return Optional.of("it is not a JSON object");
        }
        Set<ValidationMessage> broken = metaSchema.validate(node);
        if (!broken.isEmpty()) {
            return Optional.of("it breaks the JSON Schema 2020-12 rules: " + broken.stream()
                    .limit(3).map(ValidationMessage::getMessage).collect(Collectors.joining("; ")));
        }
        try {
            schemaFactory.getSchema(node);
        } catch (Exception e) {
            return Optional.of("it could not be compiled: " + e.getMessage());
        }
        return Optional.empty();
    }
}
