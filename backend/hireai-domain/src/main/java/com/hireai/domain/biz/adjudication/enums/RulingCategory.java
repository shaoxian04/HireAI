// RulingCategory.java
package com.hireai.domain.biz.adjudication.enums;

/** The arbitrator's verdict category; maps deterministically to settlement (Inv #3). */
public enum RulingCategory {
    FULFILLED,            // → settleAccepted (85/15)
    PARTIALLY_FULFILLED,  // → settleSplit (half refund / half 85-15)
    NOT_FULFILLED         // → settleRejected (full refund)
}
