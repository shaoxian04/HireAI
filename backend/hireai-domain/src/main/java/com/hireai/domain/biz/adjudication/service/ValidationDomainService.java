package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;

/**
 * Automated acceptance: judges a result against the Task's frozen Contract (Invariant #4, ADR 0009),
 * and refuses, at submit or registration, a Contract or Offer whose terms could not be checked (D8).
 */
public interface ValidationDomainService {

    /**
     * Judges a result against the Contract the Task froze at submit. A {@code file_basic} result
     * whose link is HTTPS is judged on {@code delivery}, its DeliverySnapshot or why there is none
     * (D24); {@code delivery} may be null only when {@link #needsDelivery} is false.
     */
    ValidationReportModel validate(Contract contract, TaskResultModel result, DeliveryOutcome delivery, int attemptNo);

    /** {@link #validate(Contract, TaskResultModel, DeliveryOutcome, int)} for a result that needs no delivery. */
    default ValidationReportModel validate(Contract contract, TaskResultModel result, int attemptNo) {
        return validate(contract, result, null, attemptNo);
    }

    /** True when the result's deliverable must be fetched and frozen before it can be judged. */
    boolean needsDelivery(Contract contract, TaskResultModel result);

    /**
     * The most bytes the deliverable may have: the Contract's {@code max_bytes} when set, never more
     * than {@code platformLimit}. The fetch reads one byte past it to tell "at the limit" from "over".
     */
    long deliveryByteLimit(Contract contract, long platformLimit);

    /**
     * Judges a result of a Task submitted before Contracts existed (its Contract is null) against
     * its frozen output spec, through the same D39 mapping. A blank or prose schema is still
     * recorded as {@code SCHEMA_SKIPPED} for these Tasks.
     */
    ValidationReportModel validate(OutputSpec legacySpec, TaskResultModel result, int attemptNo);

    /**
     * Throws VALIDATION_ERROR when the Contract's terms could not be checked as written: a schema
     * that is not a valid JSON Schema, or a {@code min_length} above its {@code max_length}.
     */
    void requireCheckable(Contract contract);

    /** Throws VALIDATION_ERROR when an Offer lists a schema that is not a valid JSON Schema. */
    void requireCheckable(Offer offer);
}
