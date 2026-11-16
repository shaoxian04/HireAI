package com.hireai.domain.biz.acceptance.check.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/**
 * Something the Agent itself reported with its result (spec D7): the brief or source made a term
 * impossible to meet, say. A reported anomaly sends the result to the Client's review instead of a
 * deterministic rejection, and the Agent is paid if the Client confirms it by accepting.
 * {@code check} optionally names the Check Library entry it explains.
 */
public record SelfReportedAnomaly(String check, String description) {

    public static final int MAX_DESCRIPTION = 1000;

    public SelfReportedAnomaly {
        if (description == null || description.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "An anomaly needs a description");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "An anomaly's description is at most " + MAX_DESCRIPTION + " characters");
        }
        description = description.strip();
        check = check == null || check.isBlank() ? null : check.strip();
    }
}
