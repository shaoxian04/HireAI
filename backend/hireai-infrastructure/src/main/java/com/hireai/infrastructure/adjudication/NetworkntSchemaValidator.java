package com.hireai.infrastructure.adjudication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class NetworkntSchemaValidator implements SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

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
}
