package com.hireai.domain.biz.task.webhookdelivery;

import com.hireai.utility.hash.HmacSha256;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stripe-style webhook signature: HMAC-SHA256 over "{ts}.{body}", header "t=<ts>,v1=<hex>".
 * Used outbound (client webhooks, Agent WorkOrders) and inbound (Agent result callbacks).
 */
public final class WebhookSignature {

    /** How far a signature's timestamp may be from the verifier's clock, either way. */
    public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private WebhookSignature() {}

    public static String header(String secret, long tsEpochSeconds, String body) {
        String v1 = HmacSha256.hexOf(secret, tsEpochSeconds + "." + body);
        return "t=" + tsEpochSeconds + ",v1=" + v1;
    }

    /**
     * True iff {@code header} carries exactly one numeric {@code t} within {@code tolerance} of
     * {@code now} and at least one {@code v1} equal (constant-time) to HMAC-SHA256(secret,
     * "{t}." + body) over the raw {@code body} bytes. Anything missing or malformed is false.
     */
    public static boolean verify(String secret, String header, byte[] body, Instant now, Duration tolerance) {
        if (secret == null || secret.isEmpty() || header == null || header.isBlank()) {
            return false;
        }
        Long ts = null;
        List<String> candidates = new ArrayList<>();
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t")) {
                if (ts != null || !value.matches("\\d{1,19}")) {
                    return false;
                }
                try {
                    ts = Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    return false;
                }
            } else if (key.equals("v1")) {
                candidates.add(value);
            }
        }
        if (ts == null || candidates.isEmpty()) {
            return false;
        }
        long skew = Math.abs(now.getEpochSecond() - ts);
        if (skew > tolerance.getSeconds()) {
            return false;
        }
        byte[] expected = HmacSha256.hexOf(secret, (ts + ".").getBytes(StandardCharsets.UTF_8),
                body == null ? new byte[0] : body).getBytes(StandardCharsets.US_ASCII);
        boolean match = false;
        for (String candidate : candidates) {
            // Every candidate is compared (no early exit), each in constant time.
            match |= MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII));
        }
        return match;
    }
}
