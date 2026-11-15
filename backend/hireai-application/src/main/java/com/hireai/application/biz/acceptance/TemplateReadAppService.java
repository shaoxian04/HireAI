package com.hireai.application.biz.acceptance;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.List;

/**
 * Read use cases over the platform's Templates, plus the conformance checks every write path that
 * creates a Contract or an Offer runs before persisting it (ADR 0009).
 */
public interface TemplateReadAppService {

    /** Every published Template version, ordered by key then version. */
    List<TemplateModel> list();

    /** One Template version; NOT_FOUND when it does not exist. */
    TemplateModel get(TemplateRef ref);

    /**
     * The Contract names an existing, ACTIVE Template version and every parameter is inside its
     * bounds, and its terms can be checked (a schema must be a valid JSON Schema, spec D8);
     * VALIDATION_ERROR otherwise (an unknown Template included).
     */
    void requireValid(Contract contract);

    /**
     * Every Offer term names an existing Template version with in-bounds ranges, and every schema
     * it lists is a valid JSON Schema; VALIDATION_ERROR otherwise.
     */
    void requireValid(Offer offer);
}
