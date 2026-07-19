package com.hireai.domain.biz.webhook;

import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Thin webhook JSON bodies. UUID/enum/ISO-instant/BigDecimal fields are structurally safe to interpolate;
 * the one free-text field ({@code reason}) is JSON-string-escaped so a caller passing an unclean token can
 * never emit malformed or injected JSON on this signed, externally-delivered payload. Kept dependency-free
 * (no Jackson) to preserve domain-layer purity.
 */
public final class WebhookPayloads {
    private WebhookPayloads() {}

    public static String completed(UUID eventId, UUID taskId, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_COMPLETED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"occurred_at\":\"" + at + "\"}";
    }

    public static String failed(UUID eventId, UUID taskId, String reason, BigDecimal refundedAmount, Instant at) {
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"" + WebhookEventType.TASK_FAILED.wire()
             + "\",\"task_id\":\"" + taskId + "\",\"reason\":\"" + jsonEscape(reason)
             + "\",\"refunded\":" + refundedAmount.toPlainString() + ",\"occurred_at\":\"" + at + "\"}";
    }

    /** Escapes a string for safe embedding inside a JSON double-quoted value (RFC 8259 §7). */
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
