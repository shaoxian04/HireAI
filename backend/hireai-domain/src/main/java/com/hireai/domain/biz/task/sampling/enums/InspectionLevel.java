package com.hireai.domain.biz.task.sampling.enums;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * ISO 2859-1 general inspection levels, least to most strict (spec S8, D21). A multi-Item Task is
 * inspected at {@link #II} unless the buyer chose a stricter level the Agent's Offer allows. There are
 * no switching rules: the level never changes with track record.
 */
public enum InspectionLevel {
    I,
    II,
    III;

    /** The platform's default level. */
    public static final InspectionLevel DEFAULT = II;

    public boolean isStricterThan(InspectionLevel other) {
        return ordinal() > other.ordinal();
    }

    /**
     * The level a buyer asked for, checked against the strictest level the Offer accepts: null means
     * the default; a level below the default is refused (a buyer may only inspect more, not less).
     */
    public static InspectionLevel chosen(InspectionLevel requested, InspectionLevel strictestAccepted) {
        InspectionLevel ceiling = strictestAccepted == null ? DEFAULT : strictestAccepted;
        if (requested == null) {
            return DEFAULT;
        }
        if (DEFAULT.isStricterThan(requested)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "inspectionLevel: a buyer may choose a stricter level than " + DEFAULT + ", not a looser one");
        }
        if (requested.isStricterThan(ceiling)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "inspectionLevel: this agent's Offer accepts inspection up to level " + ceiling
                            + "; got " + requested);
        }
        return requested;
    }
}
