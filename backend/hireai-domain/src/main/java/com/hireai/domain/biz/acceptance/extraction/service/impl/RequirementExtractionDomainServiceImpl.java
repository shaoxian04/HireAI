package com.hireai.domain.biz.acceptance.extraction.service.impl;

import com.hireai.domain.biz.acceptance.check.service.CheckLibrary;
import com.hireai.domain.biz.acceptance.check.service.CheckLibrary.EntryParameters;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.extraction.enums.ExtractionStatus;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;
import com.hireai.domain.biz.acceptance.extraction.model.ExtractedRequirement;
import com.hireai.domain.biz.acceptance.extraction.model.ProposedRequirement;
import com.hireai.domain.biz.acceptance.extraction.service.RequirementExtractionDomainService;
import com.hireai.domain.biz.acceptance.template.enums.ParameterUnit;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/** See {@link RequirementExtractionDomainService}. */
public class RequirementExtractionDomainServiceImpl implements RequirementExtractionDomainService {

    private static final int MAX_SET_SIZE = 64;

    @Override
    public ContractExtraction adopt(TemplateModel template, Contract base, List<ProposedRequirement> proposals,
                                    Consumer<Contract> requireValid) {
        if (!template.ref().equals(base.template())) {
            throw new IllegalArgumentException("The base Contract names " + base.template() + ", not " + template.ref());
        }
        Map<String, ParameterValue> adopted = new TreeMap<>(base.params());
        List<ExtractedRequirement> out = new ArrayList<>();
        for (ProposedRequirement proposal : proposals == null ? List.<ProposedRequirement>of() : proposals) {
            if (out.size() == MAX_REQUIREMENTS) {
                break;
            }
            if (proposal == null || proposal.text() == null || proposal.text().isBlank()) {
                continue;
            }
            String text = clip(proposal.text().strip());
            Vetted vetted = vet(template, proposal, adopted, requireValid);
            if (vetted.reason() != null) {
                out.add(ExtractedRequirement.unmapped(text, vetted.reason()));
            } else {
                adopted.putAll(vetted.params());
                out.add(ExtractedRequirement.mapped(text, proposal.check().strip(), vetted.params()));
            }
        }
        return new ContractExtraction(template.ref(), ExtractionStatus.EXTRACTED,
                new Contract(template.ref(), adopted), out);
    }

    /** The parameters a mapping sets, or why it stays unmapped. */
    private record Vetted(Map<String, ParameterValue> params, String reason) {
        static Vetted unmapped(String reason) {
            return new Vetted(Map.of(), reason);
        }
    }

    private Vetted vet(TemplateModel template, ProposedRequirement proposal, Map<String, ParameterValue> adopted,
                       Consumer<Contract> requireValid) {
        String check = proposal.check() == null ? "" : proposal.check().strip();
        if (check.isEmpty()) {
            return Vetted.unmapped("No automatic check fits it unambiguously, so it is not checked automatically.");
        }
        Optional<EntryParameters> entry = CheckLibrary.parametersOf(check);
        if (!template.checks().contains(check) || entry.isEmpty()) {
            return Vetted.unmapped(template.ref() + " has no check named " + clip(check) + ".");
        }
        if (CheckLibrary.isFinding(check)) {
            return Vetted.unmapped(check + " is a Finding, reported to you, not a check on the Agent's work.");
        }
        Map<String, ParameterValue> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> raw : proposal.params().entrySet()) {
            String name = raw.getKey();
            if (raw.getValue() == null) {
                continue; // structured output sends every parameter of the Template; null means "not set"
            }
            if (name == null || !entry.get().reads().contains(name)) {
                return Vetted.unmapped(check + " does not read a parameter named " + clip(String.valueOf(name)) + ".");
            }
            Optional<ParameterValue> value = valueOf(raw.getValue());
            if (value.isEmpty()) {
                return Vetted.unmapped("The value for " + name + " is not a number, text, list of texts or yes/no.");
            }
            params.put(name, value.get());
        }
        for (String name : params.keySet()) {
            Optional<ParameterUnit> unit = ParameterUnit.of(name);
            Optional<String> other = unit.flatMap(u -> u.otherUnitNamedIn(proposal.text()));
            if (other.isPresent()) {
                return Vetted.unmapped("It counts " + other.get() + ", but " + name + " is measured in "
                        + unit.get().words() + ", so it is not checked automatically.");
            }
        }
        if (!entry.get().alwaysRuns() && params.keySet().stream().noneMatch(entry.get().activatedBy()::contains)) {
            return Vetted.unmapped(check + " runs only when " + String.join(" or ", new TreeSet<>(entry.get().activatedBy()))
                    + " is set, and the requirement sets none of them.");
        }
        for (Map.Entry<String, ParameterValue> set : params.entrySet()) {
            ParameterValue earlier = adopted.get(set.getKey());
            if (earlier != null && !earlier.equals(set.getValue())) {
                return Vetted.unmapped("It conflicts with an earlier term for " + set.getKey() + ", which is kept.");
            }
        }
        Map<String, ParameterValue> candidate = new TreeMap<>(adopted);
        candidate.putAll(params);
        try {
            requireValid.accept(new Contract(template.ref(), candidate));
        } catch (DomainException e) {
            return Vetted.unmapped("It does not fit " + template.ref() + ": " + clip(e.getMessage()));
        }
        return new Vetted(params, null);
    }

    @Override
    public void requireNamedChecks(TemplateModel template, Contract contract, List<String> checks) {
        if (checks == null) {
            return;
        }
        for (String check : checks) {
            if (check == null || !template.checks().contains(check) || CheckLibrary.parametersOf(check).isEmpty()) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "Template " + template.ref()
                        + " has no check " + clip(String.valueOf(check)) + " (it has: "
                        + String.join(", ", template.checks()) + ")");
            }
            EntryParameters entry = CheckLibrary.parametersOf(check).get();
            if (!entry.alwaysRuns() && contract.params().keySet().stream().noneMatch(entry.activatedBy()::contains)) {
                throw new DomainException(ResultCode.VALIDATION_ERROR, "Check " + check + " runs only when "
                        + String.join(" or ", new TreeSet<>(entry.activatedBy())) + " is set in contract.params");
            }
        }
    }

    /** A raw JSON value as a parameter value, by its JSON kind; empty for anything else. */
    private static Optional<ParameterValue> valueOf(Object raw) {
        try {
            if (raw instanceof Boolean b) {
                return Optional.of(new ParameterValue.Flag(b));
            }
            if (raw instanceof Number n) {
                return Optional.of(new ParameterValue.Number(new BigDecimal(n.toString())));
            }
            if (raw instanceof String s) {
                return Optional.of(new ParameterValue.Text(s));
            }
            if (raw instanceof Collection<?> values && values.size() <= MAX_SET_SIZE
                    && values.stream().allMatch(v -> v instanceof String)) {
                Set<String> set = new LinkedHashSet<>();
                values.forEach(v -> set.add((String) v));
                return Optional.of(new ParameterValue.TextSet(set));
            }
        } catch (NumberFormatException e) {
            return Optional.empty(); // NaN, Infinity
        }
        return Optional.empty();
    }

    private static String clip(String text) {
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }
}
