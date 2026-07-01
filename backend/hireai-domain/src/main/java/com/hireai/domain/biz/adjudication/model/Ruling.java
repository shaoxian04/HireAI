package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;

import java.time.Instant;
import java.util.Objects;

/**
 * A single ruling in a dispute's append-only history: the tier, the verdict category, the
 * human-readable rationale, who decided it, and when. Money is computed from {@code category}
 * at apply-time (Invariant #3) — the rationale is never in the money path.
 */
public record Ruling(int tier, RulingCategory category, String rationale,
                     RulingDecidedBy decidedBy, Instant decidedAt) {

    public Ruling {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
