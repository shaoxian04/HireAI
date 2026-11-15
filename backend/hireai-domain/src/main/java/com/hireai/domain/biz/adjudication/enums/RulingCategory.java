package com.hireai.domain.biz.adjudication.enums;

import java.util.EnumSet;
import java.util.Set;

/** The arbitrator's verdict category; maps deterministically to settlement (Inv #3). */
public enum RulingCategory {
    FULFILLED,            // → settleAccepted (85/15)
    PARTIALLY_FULFILLED,  // → settleSplit (half refund / half 85-15)
    NOT_FULFILLED;        // → settleRejected (full refund)

    /**
     * The sides this ruling goes against, wholly or in part: they bear the dispute's costs (D43) and
     * are the ones who may appeal it (D42). A split goes partly against both.
     */
    public Set<DisputeParty> partiesItGoesAgainst() {
        return switch (this) {
            case FULFILLED -> EnumSet.of(DisputeParty.CLIENT);
            case NOT_FULFILLED -> EnumSet.of(DisputeParty.BUILDER);
            case PARTIALLY_FULFILLED -> EnumSet.of(DisputeParty.CLIENT, DisputeParty.BUILDER);
        };
    }
}
