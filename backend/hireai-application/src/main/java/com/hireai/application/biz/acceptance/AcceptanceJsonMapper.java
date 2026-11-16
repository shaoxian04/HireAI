package com.hireai.application.biz.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.model.ParameterBounds;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written JSON(B) mapping of the acceptance value objects, so the domain stays
 * annotation-free and the stored shapes are deliberate. The shapes are documented in
 * {@code V39__templates_offers_contracts.sql}, whose backfill writes the same Offer JSON.
 *
 * <p>Contract parameter values are natural JSON: a number ({@code max}/{@code min}), a string
 * ({@code enum}), an array of strings ({@code subset}) or a boolean ({@code flag}). Offer ranges are
 * tagged objects: {@code {"bound":n}}, {@code {"oneOf":[...]}}, {@code {"any":true}},
 * {@code {"acceptsTrue":b}}. Shared by the task and agent repositories and the template repository.
 */
@Component
public class AcceptanceJsonMapper {

    private final ObjectMapper objectMapper;

    public AcceptanceJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---- Contract ----

    public String toJson(Contract contract) {
        ObjectNode root = refNode(contract.template());
        ObjectNode params = root.putObject("params");
        contract.params().forEach((name, value) -> params.set(name, valueNode(value)));
        return write(root);
    }

    public Contract contractFromJson(String json) {
        JsonNode root = read(json);
        Map<String, ParameterValue> params = new LinkedHashMap<>();
        root.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), value(e.getKey(), e.getValue())));
        return new Contract(ref(root), params);
    }

    private JsonNode valueNode(ParameterValue value) {
        return switch (value) {
            case ParameterValue.Number n -> objectMapper.getNodeFactory().numberNode(n.value());
            case ParameterValue.Text t -> objectMapper.getNodeFactory().textNode(t.value());
            case ParameterValue.TextSet s -> strings(s.values());
            case ParameterValue.Flag f -> objectMapper.getNodeFactory().booleanNode(f.value());
        };
    }

    private static ParameterValue value(String name, JsonNode node) {
        if (node.isNumber()) {
            return new ParameterValue.Number(node.decimalValue());
        }
        if (node.isTextual()) {
            return new ParameterValue.Text(node.asText());
        }
        if (node.isArray()) {
            return new ParameterValue.TextSet(stringSet(node));
        }
        if (node.isBoolean()) {
            return new ParameterValue.Flag(node.asBoolean());
        }
        throw corrupt("Contract parameter " + name + " has an unreadable value");
    }

    // ---- Offer ----

    public String toJson(Offer offer) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode terms = root.putArray("terms");
        for (OfferTerm term : offer.terms()) {
            ObjectNode termNode = refNode(term.template());
            ObjectNode params = termNode.putObject("params");
            term.params().forEach((name, range) -> params.set(name, rangeNode(range)));
            terms.add(termNode);
        }
        if (offer.strictestInspectionLevel() != null) {
            root.put("strictestInspectionLevel", offer.strictestInspectionLevel().name());
        }
        return write(root);
    }

    public Offer offerFromJson(String json) {
        JsonNode root = read(json);
        List<OfferTerm> terms = new ArrayList<>();
        for (JsonNode termNode : root.path("terms")) {
            Map<String, ParameterRange> params = new LinkedHashMap<>();
            termNode.path("params").fields()
                    .forEachRemaining(e -> params.put(e.getKey(), range(e.getKey(), e.getValue())));
            terms.add(new OfferTerm(ref(termNode), params));
        }
        JsonNode level = root.path("strictestInspectionLevel");
        return new Offer(terms, level.isTextual() ? InspectionLevel.valueOf(level.asText()) : null);
    }

    private JsonNode rangeNode(ParameterRange range) {
        ObjectNode node = objectMapper.createObjectNode();
        switch (range) {
            case ParameterRange.Bound b -> node.put("bound", b.value());
            case ParameterRange.OneOf o -> node.set("oneOf", strings(o.values()));
            case ParameterRange.AnyValue a -> node.put("any", true);
            case ParameterRange.Flag f -> node.put("acceptsTrue", f.acceptsTrue());
        }
        return node;
    }

    private static ParameterRange range(String name, JsonNode node) {
        if (node.has("bound")) {
            return new ParameterRange.Bound(node.get("bound").decimalValue());
        }
        if (node.has("oneOf")) {
            return new ParameterRange.OneOf(stringSet(node.get("oneOf")));
        }
        if (node.path("any").asBoolean(false)) {
            return new ParameterRange.AnyValue();
        }
        if (node.has("acceptsTrue")) {
            return new ParameterRange.Flag(node.get("acceptsTrue").asBoolean());
        }
        throw corrupt("Offer range for " + name + " is unreadable");
    }

    // ---- Template ----

    /** Rebuilds a Template from its {@code contract_templates} row; {@code definition} is the JSONB. */
    public TemplateModel templateFromRow(String key, int version, String status, Instant publishedAt,
                                         String definition) {
        JsonNode root = read(definition);
        List<String> checks = new ArrayList<>();
        root.path("checks").forEach(c -> checks.add(c.asText()));
        List<TemplateParameter> parameters = new ArrayList<>();
        for (JsonNode p : root.path("parameters")) {
            JsonNode b = p.path("bounds");
            List<String> allowed = null;
            if (b.has("allowedValues")) {
                allowed = new ArrayList<>();
                for (JsonNode v : b.get("allowedValues")) {
                    allowed.add(v.asText());
                }
            }
            parameters.add(new TemplateParameter(p.path("name").asText(), p.path("label").asText(),
                    p.path("description").asText(null),
                    ParameterComparison.fromWireName(p.path("comparison").asText()),
                    new ParameterBounds(decimalOrNull(b, "minimum"), decimalOrNull(b, "maximum"), allowed,
                            b.has("maxLength") ? b.get("maxLength").asInt() : null)));
        }
        Map<String, BigDecimal> settlement = new LinkedHashMap<>();
        root.path("rulingSettlement").fields()
                .forEachRemaining(e -> settlement.put(e.getKey(), e.getValue().decimalValue()));
        return new TemplateModel(new TemplateRef(key, version), root.path("title").asText(),
                root.path("description").asText(null), OutputShape.valueOf(root.path("outputShape").asText()),
                checks, parameters, ExpectationMode.valueOf(root.path("expectationMode").asText()),
                settlement, TemplateStatus.valueOf(status), publishedAt);
    }

    // ---- helpers ----

    private ObjectNode refNode(TemplateRef ref) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("template", ref.key());
        node.put("version", ref.version());
        return node;
    }

    private static TemplateRef ref(JsonNode node) {
        return new TemplateRef(node.path("template").asText(), node.path("version").asInt());
    }

    private ArrayNode strings(Set<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.has(field) ? node.get(field).decimalValue() : null;
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to serialise acceptance terms");
        }
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw corrupt("Failed to read acceptance terms");
        }
    }

    private static DomainException corrupt(String message) {
        return new DomainException(ResultCode.INTERNAL_ERROR, message);
    }
}
