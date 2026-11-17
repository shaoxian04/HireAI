package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Template aggregate root: the platform-authored, versioned definition of one kind of work
 * (ADR 0009). It fixes the deliverable's shape, the Check Library entries a Contract may use, each
 * parameter's bounds and comparison, the expectation mode, and the fraction of the price the
 * Builder is paid under each ruling category. Templates change only by Flyway migration (or a
 * future admin publication), never by Clients or Builders, and a published version's definition is
 * immutable (a DB trigger enforces it); only its status may move to RETIRED.
 *
 * <p>Read-only in the app today. The settlement map is declarative: money still moves through
 * {@code SettlementPolicy} until a later slice reads it (Invariant #3: settlement stays deterministic).
 */
public final class TemplateModel {

    /** The ruling categories a settlement map must name, matching the adjudication {@code Ruling}. */
    public static final Set<String> RULING_CATEGORIES = Set.of("FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED");

    private final TemplateRef ref;
    private final String title;
    private final String description;
    private final OutputShape outputShape;
    private final List<String> checks;
    private final Map<String, TemplateParameter> parameters;
    private final ExpectationMode expectationMode;
    private final Map<String, BigDecimal> rulingSettlement;
    private final TemplateStatus status;
    private final Instant publishedAt;

    public TemplateModel(TemplateRef ref, String title, String description, OutputShape outputShape,
                         List<String> checks, List<TemplateParameter> parameters,
                         ExpectationMode expectationMode, Map<String, BigDecimal> rulingSettlement,
                         TemplateStatus status, Instant publishedAt) {
        if (ref == null || outputShape == null || expectationMode == null || status == null) {
            throw invalid("A Template needs a reference, an output shape, an expectation mode and a status");
        }
        if (title == null || title.isBlank()) {
            throw invalid("Template " + ref + " needs a title");
        }
        this.ref = ref;
        this.title = title;
        this.description = description;
        this.outputShape = outputShape;
        this.checks = List.copyOf(checks);
        Map<String, TemplateParameter> byName = new LinkedHashMap<>();
        for (TemplateParameter parameter : parameters) {
            if (byName.put(parameter.name(), parameter) != null) {
                throw invalid("Template " + ref + " declares parameter " + parameter.name() + " twice");
            }
        }
        this.parameters = Collections.unmodifiableMap(byName);
        this.expectationMode = expectationMode;
        this.rulingSettlement = requireSettlement(ref, rulingSettlement);
        this.status = status;
        this.publishedAt = publishedAt;
    }

    private static Map<String, BigDecimal> requireSettlement(TemplateRef ref, Map<String, BigDecimal> settlement) {
        if (settlement == null || !settlement.keySet().equals(RULING_CATEGORIES)) {
            throw invalid("Template " + ref + " must settle exactly " + RULING_CATEGORIES);
        }
        for (Map.Entry<String, BigDecimal> entry : settlement.entrySet()) {
            BigDecimal fraction = entry.getValue();
            if (fraction == null || fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
                throw invalid("Template " + ref + " pays a fraction outside [0, 1] for " + entry.getKey());
            }
        }
        return Collections.unmodifiableSortedMap(new TreeMap<>(settlement));
    }

    /**
     * A new Contract must name this exact, ACTIVE version and give in-bounds values only to
     * parameters this version declares. Unset parameters are allowed.
     */
    public void requireValid(Contract contract) {
        if (!ref.equals(contract.template())) {
            throw invalid("Contract names " + contract.template() + ", not " + ref);
        }
        if (status != TemplateStatus.ACTIVE) {
            throw invalid("Template " + ref + " is retired and takes no new Contracts");
        }
        for (Map.Entry<String, ParameterValue> entry : contract.params().entrySet()) {
            parameter(entry.getKey()).requireValid(entry.getValue());
        }
    }

    /** An Offer term must name this version and give in-bounds ranges only to declared parameters. */
    public void requireValid(OfferTerm term) {
        if (!ref.equals(term.template())) {
            throw invalid("Offer term names " + term.template() + ", not " + ref);
        }
        for (Map.Entry<String, ParameterRange> entry : term.params().entrySet()) {
            parameter(entry.getKey()).offerRangeProblem(entry.getValue()).ifPresent(problem -> {
                throw invalid("Offer for " + ref + ": " + problem);
            });
        }
    }

    private TemplateParameter parameter(String name) {
        TemplateParameter parameter = parameters.get(name);
        if (parameter == null) {
            throw invalid("Template " + ref + " has no parameter " + name
                    + (parameters.isEmpty() ? " (it has none)" : " (it has: " + String.join(", ", parameters.keySet()) + ")"));
        }
        return parameter;
    }

    private static DomainException invalid(String message) {
        return new DomainException(ResultCode.VALIDATION_ERROR, message);
    }

    public TemplateRef ref() { return ref; }
    public String title() { return title; }
    public String description() { return description; }
    public OutputShape outputShape() { return outputShape; }
    public List<String> checks() { return checks; }
    public List<TemplateParameter> parameters() { return List.copyOf(parameters.values()); }
    public ExpectationMode expectationMode() { return expectationMode; }
    public Map<String, BigDecimal> rulingSettlement() { return rulingSettlement; }
    public TemplateStatus status() { return status; }
    public Instant publishedAt() { return publishedAt; }
}
