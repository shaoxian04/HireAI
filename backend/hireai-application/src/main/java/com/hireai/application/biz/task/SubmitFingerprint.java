package com.hireai.application.biz.task;

import com.hireai.utility.hash.Sha256;

import java.math.BigDecimal;

/**
 * Deterministic fingerprint of a submit payload. Fields are joined with a delimiter that cannot
 * appear un-escaped in the values' surrounding structure, then SHA-256-hashed. The budget is
 * normalized (trailing zeros stripped) so 10 and 10.00 fingerprint identically. Null fields
 * (schema/criteria may be null inside outputSpecJson upstream) are rendered as the literal "∅".
 */
public final class SubmitFingerprint {
    private static final String SEP = "␞"; // RECORD SEPARATOR symbol — not expected in payloads
    private SubmitFingerprint() {}

    public static String of(String title, String description, String category,
                            BigDecimal budget, String outputSpecJson) {
        String canonical = String.join(SEP,
                nz(title), nz(description), nz(category),
                budget == null ? "∅" : budget.stripTrailingZeros().toPlainString(),
                nz(outputSpecJson));
        return Sha256.hex(canonical);
    }

    private static String nz(String s) { return s == null ? "∅" : s; }
}
