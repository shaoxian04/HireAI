package com.hireai.domain.biz.acceptance.receipt.model;

import com.hireai.utility.canonical.JsonCanonicalizer;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * A signed receipt for one settled Item (spec S11, D40): the RFC 8785 text of its body, signed with
 * the platform's Ed25519 key {@code keyId}. {@code itemNo} is null for a single-deliverable Task.
 * Immutable and append-only; a receipt gates nothing inside HireAI. Its format is docs/details/receipts.md.
 */
public record ReceiptModel(UUID id, UUID taskId, Integer itemNo, String keyId, String body, String signature,
                           Instant issuedAt) {

    public static final String FORMAT = "hireai.receipt.v1";

    public ReceiptModel {
        if (id == null || taskId == null || issuedAt == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A receipt needs an id, a task and an issue time");
        }
        if (itemNo != null && itemNo < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Item numbers start at 1");
        }
        if (isBlank(keyId) || isBlank(body) || isBlank(signature)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A receipt needs its key id, body and signature");
        }
    }

    /**
     * The downloadable document: the signed body and its signature. Both members are already in
     * RFC 8785 order, so the envelope itself is canonical JSON too.
     */
    public String envelope() {
        return "{\"receipt\":" + body + ",\"signature\":{\"alg\":\"Ed25519\",\"keyId\":" + quote(keyId)
                + ",\"value\":" + quote(signature) + "}}";
    }

    private static String quote(String s) {
        return JsonCanonicalizer.canonicalize(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
