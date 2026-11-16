package com.hireai.infrastructure.adjudication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.acceptance.check.model.ExtractedInvoice;
import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;
import com.hireai.domain.biz.acceptance.check.service.InvoiceReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an {@code ap_invoice_extraction} result with Jackson, without judging it: a field of the wrong
 * JSON type is left null and named in {@code typeProblems}. Amounts must be JSON numbers (read exactly,
 * as BigDecimal, never through a double); a number given as a string is a type problem, so the Agent
 * learns to send numbers. {@code po_number} may be null.
 */
@Component
public class JacksonInvoiceReader implements InvoiceReader {

    /** Results larger than this are not read as an invoice at all. */
    static final int MAX_CHARS = 200_000;
    private static final int MAX_LINES = 500;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Override
    public InvoiceReading read(String payload) {
        if (payload == null || payload.isBlank()) {
            return new InvoiceReading.Unreadable("the result is empty");
        }
        if (payload.length() > MAX_CHARS) {
            return new InvoiceReading.Unreadable("the result is longer than " + MAX_CHARS + " characters");
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return new InvoiceReading.Unreadable("it is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            return new InvoiceReading.Unreadable("it is JSON, but not an object");
        }
        List<String> problems = new ArrayList<>();
        List<ExtractedInvoice.Line> lines = null;
        JsonNode lineNodes = root.get("line_items");
        if (lineNodes != null && !lineNodes.isNull()) {
            if (!lineNodes.isArray()) {
                problems.add("line_items");
            } else if (lineNodes.size() > MAX_LINES) {
                problems.add("line_items (more than " + MAX_LINES + " lines)");
            } else {
                lines = new ArrayList<>();
                for (int i = 0; i < lineNodes.size(); i++) {
                    JsonNode line = lineNodes.get(i);
                    String at = "line_items[" + (i + 1) + "].";
                    if (!line.isObject()) {
                        problems.add("line_items[" + (i + 1) + "]");
                        lines.add(new ExtractedInvoice.Line(null, null, null, null, null));
                        continue;
                    }
                    lines.add(new ExtractedInvoice.Line(text(line, "description", at, problems),
                            number(line, "quantity", at, problems), number(line, "unit_price", at, problems),
                            number(line, "amount", at, problems), number(line, "tax_amount", at, problems)));
                }
            }
        }
        return new InvoiceReading.Read(new ExtractedInvoice(
                text(root, "supplier_name", "", problems), text(root, "supplier_tax_id", "", problems),
                text(root, "invoice_number", "", problems), text(root, "invoice_date", "", problems),
                text(root, "currency", "", problems), text(root, "po_number", "", problems),
                number(root, "subtotal", "", problems), number(root, "tax_total", "", problems),
                number(root, "grand_total", "", problems), number(root, "tax_rate", "", problems),
                lines, problems));
    }

    private static String text(JsonNode node, String field, String at, List<String> problems) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            problems.add(at + field);
            return null;
        }
        return value.asText().strip();
    }

    private static BigDecimal number(JsonNode node, String field, String at, List<String> problems) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            problems.add(at + field);
            return null;
        }
        return value.decimalValue();
    }
}
