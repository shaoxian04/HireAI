package com.hireai.application.biz.acceptance.receipt.impl;

import com.hireai.utility.hash.HmacSha256;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Pseudonymous party references for receipts, which leave HireAI (auditors, insurers): never a raw
 * internal id, but {@code hex(HMAC-SHA256(key, taskId "|" role "|" id))}. Per task, so refs on two
 * receipts can't be linked to one user; HireAI resolves a ref by recomputing it. The key comes from
 * {@code hireai.receipts.party-key} (the environment; never committed). Without one, a key is
 * generated per process with a warning: dev and tests only, since refs then stop being resolvable
 * after a restart.
 */
@Slf4j
@Component
class ReceiptPartyRefs {

    static final String CLIENT = "CLIENT";
    static final String BUILDER = "BUILDER";
    static final String AGENT_VERSION = "AGENT_VERSION";

    private final String key;

    ReceiptPartyRefs(@Value("${hireai.receipts.party-key:}") String key) {
        if (key == null || key.isBlank()) {
            log.warn("hireai.receipts.party-key is not set: receipt party refs use a key generated for this process. "
                    + "They cannot be resolved after a restart; set RECEIPT_PARTY_KEY in real deploys.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = HexFormat.of().formatHex(random);
        } else if (key.trim().length() < 32) {
            throw new IllegalStateException("hireai.receipts.party-key must be at least 32 characters");
        } else {
            this.key = key.trim();
        }
    }

    /** The ref of {@code id} acting as {@code role} on {@code taskId}; null when there is no such party. */
    String ref(UUID taskId, String role, UUID id) {
        return id == null ? null : HmacSha256.hexOf(key, taskId + "|" + role + "|" + id);
    }
}
