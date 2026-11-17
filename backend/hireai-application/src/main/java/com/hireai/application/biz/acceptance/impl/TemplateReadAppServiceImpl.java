package com.hireai.application.biz.acceptance.impl;

import com.hireai.application.biz.acceptance.TemplateReadAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;
import com.hireai.domain.biz.acceptance.extraction.service.RequirementExtractionDomainService;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.acceptance.template.info.TemplateLifecycle;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.acceptance.template.model.TemplateTransition;
import com.hireai.domain.biz.acceptance.template.model.TemplateVersions;
import com.hireai.domain.biz.acceptance.template.repository.TemplateRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Loads Templates and delegates the bounds rules to the {@link TemplateModel} aggregate and the
 * version transition to {@link TemplateVersions}, then asks the {@link ValidationDomainService}
 * whether the terms could actually be checked (spec D8): a schema that is not a valid JSON Schema is
 * refused here rather than silently skipped later.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateReadAppServiceImpl implements TemplateReadAppService {

    private final TemplateRepository templateRepository;
    private final ValidationDomainService validationDomainService;
    private final TemplateTransition templateTransition;
    /** The app's Clock bean: the transition is measured from now. */
    private final Clock clock;
    private final RequirementExtractionDomainService requirementExtractionDomainService;

    @Override
    public List<TemplateModel> list() {
        return templateRepository.findAll();
    }

    @Override
    public Map<TemplateRef, TemplateLifecycle> lifecycles() {
        Instant now = clock.instant();
        Map<TemplateRef, TemplateLifecycle> out = new LinkedHashMap<>();
        templateRepository.findAll().stream()
                .collect(Collectors.groupingBy(t -> t.ref().key(), LinkedHashMap::new, Collectors.toList()))
                .values()
                .forEach(line -> {
                    TemplateVersions versions = TemplateVersions.of(line, templateTransition.period());
                    line.forEach(t -> out.put(t.ref(), versions.lifecycleOf(t.ref(), now)));
                });
        return out;
    }

    @Override
    public TemplateModel get(TemplateRef ref) {
        return templateRepository.find(ref)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Template not found: " + ref));
    }

    @Override
    public TemplateModel current(String key) {
        List<TemplateModel> line = templateRepository.findVersions(key);
        return TemplateVersions.of(line, templateTransition.period()).current()
                .orElseThrow(() -> new DomainException(ResultCode.VALIDATION_ERROR, line.isEmpty()
                        ? "Unknown Template " + key : "Template " + key + " takes no new Contracts"));
    }

    @Override
    public void requireValid(Contract contract) {
        known(contract.template()).requireValid(contract);
        versionsOf(contract.template()).requireTakesNewContracts(contract.template(), clock.instant());
        validationDomainService.requireCheckable(contract);
    }

    @Override
    public void requireNamedChecks(Contract contract, List<String> checks) {
        if (checks != null) {
            requirementExtractionDomainService.requireNamedChecks(known(contract.template()), contract, checks);
        }
    }

    @Override
    public ContractResolution resolveNew(Contract requested, Predicate<ContractTerms> covered) {
        known(requested.template());
        ContractResolution resolution = versionsOf(requested.template())
                .resolve(requested, clock.instant(), covered);
        validationDomainService.requireCheckable(resolution.terms().contract());
        return resolution;
    }

    @Override
    public Optional<VersionFallback> fallbackOf(Contract frozen) {
        List<TemplateModel> line = templateRepository.findVersions(frozen.template().key());
        if (line.isEmpty()) {
            return Optional.empty();
        }
        TemplateVersions versions = TemplateVersions.of(line, templateTransition.period());
        TemplateLifecycle lifecycle = versions.lifecycleOf(frozen.template(), clock.instant());
        if (lifecycle.current() || !lifecycle.takesNewContracts()) {
            return Optional.empty();
        }
        return Optional.of(new VersionFallback(versions.current().orElseThrow().ref(), frozen.template(),
                lifecycle.takesNewContractsUntil()));
    }

    @Override
    public void requireValid(Offer offer) {
        Instant now = clock.instant();
        for (OfferTerm term : offer.terms()) {
            known(term.template()).requireValid(term);
            // An Offer term for a retired or closed version could never be matched (spec D36).
            versionsOf(term.template()).requireTakesNewContracts(term.template(), now);
        }
        validationDomainService.requireCheckable(offer);
    }

    @Override
    public ContractTerms termsFor(Contract contract) {
        return new ContractTerms(known(contract.template()), contract);
    }

    private TemplateVersions versionsOf(TemplateRef ref) {
        return TemplateVersions.of(templateRepository.findVersions(ref.key()), templateTransition.period());
    }

    private TemplateModel known(TemplateRef ref) {
        return templateRepository.find(ref)
                .orElseThrow(() -> new DomainException(ResultCode.VALIDATION_ERROR, "Unknown Template " + ref));
    }
}
