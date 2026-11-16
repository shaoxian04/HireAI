package com.hireai.application.biz.acceptance;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.acceptance.template.info.TemplateLifecycle;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Read use cases over the platform's Templates, plus the conformance checks every write path that
 * creates a Contract or an Offer runs before persisting it (ADR 0009), and the version transition
 * between Template versions (spec D36).
 */
public interface TemplateReadAppService {

    /** Every published Template version, ordered by key then version. */
    List<TemplateModel> list();

    /** Where every published version stands in its line now (current, in transition, closed). */
    Map<TemplateRef, TemplateLifecycle> lifecycles();

    /** One Template version; NOT_FOUND when it does not exist. */
    TemplateModel get(TemplateRef ref);

    /**
     * The current version of the Template {@code key} (its newest ACTIVE version, spec D36);
     * VALIDATION_ERROR when the key is unknown or its line is retired.
     */
    TemplateModel current(String key);

    /**
     * The Contract names an existing Template version that takes new Contracts (the current one, or
     * the previous one during its transition), every parameter is inside its bounds, and its terms can
     * be checked (a schema must be a valid JSON Schema, spec D8); VALIDATION_ERROR otherwise (an
     * unknown Template included).
     */
    void requireValid(Contract contract);

    /**
     * A structured Contract that names the Check Library entries it relies on (spec D15): each must be
     * an entry its Template lists, switched on by the Contract's parameters; VALIDATION_ERROR otherwise
     * (an unknown Template included). Null {@code checks} checks nothing.
     */
    void requireNamedChecks(Contract contract, List<String> checks);

    /**
     * Makes a new Contract from {@code requested} (spec D36): it uses the current version of the
     * requested Template, or during a transition falls back to the previous version when
     * {@code covered} accepts no Offer for the current one; the result says so. The chosen Contract is
     * valid as {@link #requireValid(Contract)} checks it. VALIDATION_ERROR when it fits no live version.
     */
    ContractResolution resolveNew(Contract requested, Predicate<ContractTerms> covered);

    /**
     * The Contract with its Template version, for Offer coverage (#91). VALIDATION_ERROR when the
     * Template is unknown. Does not check the bounds or the Template's status (a frozen Contract stays
     * judged under its own version).
     */
    ContractTerms termsFor(Contract contract);

    /**
     * For a frozen Contract made under the previous version while it is in transition: that it was,
     * which version is current, and when the transition ends. Empty otherwise.
     */
    Optional<VersionFallback> fallbackOf(Contract frozen);

    /**
     * Every Offer term names an existing Template version that takes new Contracts (an Offer naming a
     * retired or closed version could never be matched), with in-bounds ranges, and every schema it
     * lists is a valid JSON Schema; VALIDATION_ERROR otherwise.
     */
    void requireValid(Offer offer);
}
