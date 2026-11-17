package com.hireai.application.biz.acceptance.receipt.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;
import com.hireai.utility.canonical.JsonCanonicalizer;
import com.hireai.utility.hash.Sha256;
import com.hireai.utility.signature.DidKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a stored receipt in two open formats (spec D40; docs/details/receipts.md#exports, schemas in
 * docs/formats). Both are derived from the stored signed body alone, so they state nothing the
 * receipt does not, and neither is stored or read back: like the receipt, they gate nothing.
 * <ul>
 *   <li>a W3C Verifiable Credential 2.0 carrying the receipt and its signature, secured with a Data
 *       Integrity proof, cryptosuite {@code eddsa-jcs-2022} (W3C vc-di-eddsa §3.3), by the active key
 *       named as a {@code did:key};</li>
 *   <li>OpenTelemetry {@code gen_ai.evaluation.result} events as an OTLP/JSON logs export request.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ReceiptExporter {

    static final String VC_V2_CONTEXT = "https://www.w3.org/ns/credentials/v2";
    private static final String ISSUER_DEPENDENT = "https://www.w3.org/ns/credentials/issuer-dependent#";
    static final String EVALUATION_EVENT = "gen_ai.evaluation.result";
    private static final int SEVERITY_INFO = 9;

    private final ReceiptSigner signer;
    private final ObjectMapper objectMapper;

    // ---- W3C VC 2.0 ----

    String credential(ReceiptModel receipt, Instant now) {
        Map<String, Object> body = parse(receipt.body());
        List<Object> context = List.of(VC_V2_CONTEXT, map(
                // The receipt stays a JSON literal (@json): its own members are not re-interpreted as
                // linked data, and it is carried byte for byte under RFC 8785.
                "receipt", map("@id", ISSUER_DEPENDENT + "hireaiReceipt", "@type", "@json"),
                "receiptSignature", map("@id", ISSUER_DEPENDENT + "hireaiReceiptSignature", "@type", "@json")));
        byte[] key = activeKey();

        Map<String, Object> credential = map(
                "@context", context,
                "id", "urn:uuid:" + receipt.id(),
                "type", List.of("VerifiableCredential", "HireAIReceiptCredential"),
                "issuer", DidKey.of(key),
                "validFrom", body.get("issuedAt"),
                "credentialSubject", map(
                        "receipt", body,
                        "receiptSignature", map("alg", "Ed25519", "keyId", receipt.keyId(), "value", receipt.signature())));

        // eddsa-jcs-2022 "Create Proof": the proof options carry the document's @context.
        Map<String, Object> proof = map(
                "type", "DataIntegrityProof",
                "cryptosuite", "eddsa-jcs-2022",
                "created", now.truncatedTo(ChronoUnit.SECONDS).toString(),
                "verificationMethod", DidKey.verificationMethod(key),
                "proofPurpose", "assertionMethod",
                "@context", context);
        byte[] hashData = concat(sha256(JsonCanonicalizer.canonicalize(proof)),
                sha256(JsonCanonicalizer.canonicalize(credential)));
        byte[] signature = Base64.getUrlDecoder().decode(signer.sign(hashData));
        proof.put("proofValue", "z" + DidKey.base58btc(signature));
        credential.put("proof", proof);
        return JsonCanonicalizer.canonicalize(credential);
    }

    /** The key {@link ReceiptSigner#sign} signs with, from what the signer publishes. */
    private byte[] activeKey() {
        return signer.publishedKeys().stream().filter(ReceiptSigner.PublicKey::active).findFirst()
                .orElseThrow(() -> new IllegalStateException("No active receipt signing key"))
                .raw();
    }

    // ---- OpenTelemetry gen_ai.evaluation.result ----

    @SuppressWarnings("unchecked")
    String otelEvents(ReceiptModel receipt, Instant now) {
        Map<String, Object> body = parse(receipt.body());
        Map<String, Object> contract = (Map<String, Object>) body.get("contract");
        Map<String, Object> item = (Map<String, Object>) body.get("item");
        Map<String, Object> settlement = (Map<String, Object>) body.get("settlement");
        Map<String, Object> delivery = (Map<String, Object>) body.get("delivery");
        Map<String, Object> checks = (Map<String, Object>) body.get("checks");
        String taskId = (String) ((Map<String, Object>) body.get("task")).get("id");

        List<Object> common = new ArrayList<>();
        common.add(string("gen_ai.response.id", item == null ? taskId : taskId + "/items/" + item.get("no")));
        common.add(string("hireai.receipt.id", receipt.id().toString()));
        common.add(string("hireai.receipt.format", (String) body.get("format")));
        common.add(string("hireai.receipt.sha256", Sha256.hex(receipt.envelope())));
        common.add(string("hireai.receipt.key_id", receipt.keyId()));
        common.add(string("hireai.task.id", taskId));
        if (item != null) {
            common.add(integer("hireai.item.no", ((Number) item.get("no")).longValue()));
        }
        common.add(string("hireai.contract.template", (String) contract.get("template")));
        common.add(string("hireai.contract.sha256", (String) contract.get("sha256")));

        String settledAt = (String) settlement.get("settledAt");
        Object received = delivery == null ? null : delivery.get("receivedAt");
        String checkedAt = received == null ? settledAt : (String) received;
        List<Object> events = new ArrayList<>();
        if (checks != null) {
            List<Map<String, Object>> results = (List<Map<String, Object>>) checks.get("results");
            long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
            List<String> failed = results.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed")))
                    .map(r -> (String) r.get("rule")).toList();
            String verdict = ((String) checks.get("verdict")).toLowerCase(Locale.ROOT);
            events.add(event(checkedAt, now, common, "checks", "hireai.contract_checks", verdict,
                    results.isEmpty() ? null : (double) passed / results.size(),
                    passed + " of " + results.size() + " checks passed"
                            + (failed.isEmpty() ? "" : "; failed: " + String.join(", ", failed)),
                    List.of()));
            for (Map<String, Object> r : results) {
                boolean pass = Boolean.TRUE.equals(r.get("passed"));
                events.add(event(checkedAt, now, common, "check", (String) r.get("rule"), pass ? "pass" : "fail",
                        pass ? 1.0 : 0.0, (String) r.get("detail"), List.of()));
            }
        }
        events.add(acceptance(body, settlement, settledAt, now, common));

        Map<String, Object> export = map("resourceLogs", List.of(map(
                "resource", map("attributes", List.of(string("service.name", "hireai"))),
                "scopeLogs", List.of(map(
                        "scope", map("name", "hireai.receipts", "version", body.get("format")),
                        "logRecords", events)))));
        return JsonCanonicalizer.canonicalize(export);
    }

    /** The settlement outcome; its score is the share of the price the Builder's side kept. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> acceptance(Map<String, Object> body, Map<String, Object> settlement, String settledAt,
                                           Instant now, List<Object> common) {
        BigDecimal price = new BigDecimal((String) settlement.get("price"));
        BigDecimal refunded = new BigDecimal((String) settlement.get("refunded"));
        Double score = price.signum() == 0 ? null
                : price.subtract(refunded).divide(price, 4, RoundingMode.HALF_EVEN).doubleValue();
        String outcome = (String) settlement.get("outcome");

        List<String> why = new ArrayList<>();
        List<Object> extra = new ArrayList<>();
        extra.add(string("hireai.settlement.outcome", outcome));
        Map<String, Object> ruling = (Map<String, Object>) body.get("ruling");
        if (ruling != null) {
            why.add("ruling " + ruling.get("category"));
            extra.add(string("hireai.ruling.category", (String) ruling.get("category")));
        }
        List<Map<String, Object>> judgements = (List<Map<String, Object>>) body.get("judgements");
        if (!judgements.isEmpty() && Boolean.TRUE.equals(judgements.get(judgements.size() - 1).get("automatic"))) {
            why.add("accepted automatically when the review window closed");
        }
        if (settlement.get("failureReason") != null) {
            why.add("failure " + settlement.get("failureReason"));
        }
        why.add("paid " + settlement.get("paid") + " + commission " + settlement.get("commission")
                + ", refunded " + refunded.toPlainString() + " of " + price.toPlainString());
        return event(settledAt, now, common, "acceptance", "hireai.acceptance", outcome.toLowerCase(Locale.ROOT),
                score, String.join("; ", why), extra);
    }

    private static Map<String, Object> event(String at, Instant observed, List<Object> common, String kind, String name,
                                             String label, Double score, String explanation, List<Object> extra) {
        List<Object> attributes = new ArrayList<>();
        attributes.add(string("gen_ai.evaluation.name", name));
        attributes.add(string("gen_ai.evaluation.score.label", label));
        if (score != null) {
            attributes.add(map("key", "gen_ai.evaluation.score.value", "value", map("doubleValue", score)));
        }
        if (explanation != null && !explanation.isBlank()) {
            attributes.add(string("gen_ai.evaluation.explanation", explanation));
        }
        attributes.add(string("hireai.evaluation.kind", kind));
        attributes.addAll(common);
        attributes.addAll(extra);
        return map("timeUnixNano", nanos(Instant.parse(at)),
                "observedTimeUnixNano", nanos(observed),
                "eventName", EVALUATION_EVENT,
                "severityNumber", SEVERITY_INFO,
                "severityText", "INFO",
                "attributes", attributes);
    }

    private static Map<String, Object> string(String key, String value) {
        return map("key", key, "value", map("stringValue", value));
    }

    /** OTLP/JSON writes 64-bit integers as decimal strings. */
    private static Map<String, Object> integer(String key, long value) {
        return map("key", key, "value", map("intValue", Long.toString(value)));
    }

    private static String nanos(Instant t) {
        return BigInteger.valueOf(t.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(t.getNano())).toString();
    }

    // ---- helpers ----

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored receipt body is not JSON", e);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** A mutable ordered map of key/value pairs (RFC 8785 reorders keys anyway). */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }
}
