package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side port backing the per-key spend-cap check. Both sums are computed from tasks/attribution,
 * never from a ledger (Invariant #2). Returns ZERO (not null) when a key has no attributed tasks.
 */
public interface SpendReadPort {

    /** Concurrent frozen escrow: SUM of the key's tasks NOT in a money-released terminal state. */
    BigDecimal committedFor(UUID apiKeyId);

    /** Rolling-24h velocity: SUM of budgets attributed to the key with created_at > since. */
    BigDecimal dailySpendFor(UUID apiKeyId, Instant since);
}
