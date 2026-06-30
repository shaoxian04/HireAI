// Ruling.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Immutable ruling VO. The arbitrator supplies only {@code category} + {@code rationale};
 * {@code tier} (1 in tier-1) and {@code decidedBy} are set platform-side. No money lives here (Inv #3).
 */
public record Ruling(int tier, RulingCategory category, String rationale, RulingDecidedBy decidedBy) {

    public Ruling {
        if (category == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling category is required");
        }
        if (decidedBy == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "ruling decidedBy is required");
        }
    }
}
