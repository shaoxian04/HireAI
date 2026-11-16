package com.hireai.domain.biz.ledger.settlement.info;

import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.shared.model.Money;

/**
 * How one dispute fee was borne: its full cost, the cap applied (20% of the Item price), what each
 * side was actually charged, and what the platform covered (the excess over the cap, plus anything a
 * loser's balance could not pay). {@code fullCost = clientCharged + builderCharged + platformCovered}.
 * {@code waivedReason} is set when no side was charged by rule (for example {@link #NO_AI_RULING}: the
 * arbitrator produced no ruling, so nobody bears its cost); null for an ordinary loser-pays charge.
 */
public record DisputeFeeCharge(DisputeFeeType type, Money fullCost, Money cappedAmount,
                               Money clientCharged, Money builderCharged, Money platformCovered,
                               String waivedReason) {

    /** The arbitrator failed and the admin ruled without an AI ruling (owner decision on #87). */
    public static final String NO_AI_RULING = "NO_AI_RULING";

    public DisputeFeeCharge(DisputeFeeType type, Money fullCost, Money cappedAmount,
                            Money clientCharged, Money builderCharged, Money platformCovered) {
        this(type, fullCost, cappedAmount, clientCharged, builderCharged, platformCovered, null);
    }

    /** A fee no side bears: the platform covers all of it, for {@code reason}. */
    public static DisputeFeeCharge waived(DisputeFeeType type, Money fullCost, String reason) {
        return new DisputeFeeCharge(type, fullCost, Money.ZERO, Money.ZERO, Money.ZERO, fullCost, reason);
    }
}
