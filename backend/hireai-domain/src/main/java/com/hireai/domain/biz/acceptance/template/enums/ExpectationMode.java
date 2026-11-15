package com.hireai.domain.biz.acceptance.template.enums;

/**
 * What a Contract expects of the deliverable relative to its source input (spec D7).
 * {@code NONE} for the generic Templates, which have no source document to be faithful to.
 */
public enum ExpectationMode {
    NONE,
    /** Reproduce the source faithfully, even where the source itself is wrong (the invoice default). */
    FAITHFUL_TO_SOURCE,
    /** Produce a business-valid result and report every anomaly found in the source. */
    BUSINESS_VALID
}
