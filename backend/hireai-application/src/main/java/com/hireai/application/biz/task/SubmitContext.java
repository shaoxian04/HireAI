package com.hireai.application.biz.task;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything the submit orchestration needs beyond the domain carrier: the owner (from the principal),
 * an optional client-supplied idempotency key, and — when the request came via an API key — the key id
 * and its (nullable) spend caps. The controller assembles this; the app layer never touches the
 * SecurityContext (Invariant #5).
 */
public record SubmitContext(UUID ownerId, String idempotencyKey, UUID apiKeyId,
                            BigDecimal spendCap, BigDecimal dailySpendCap) {

    public boolean hasIdempotencyKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }

    public boolean isApiKey() {
        return apiKeyId != null;
    }
}
