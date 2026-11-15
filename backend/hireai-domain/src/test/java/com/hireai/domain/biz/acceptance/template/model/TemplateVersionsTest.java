package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.info.ContractResolution;
import com.hireai.domain.biz.acceptance.template.info.TemplateLifecycle;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Template version transition (spec D36): new Contracts use the current version; while the previous
 * version is in transition a Contract falls back to it when no Offer covers the current one; after the
 * transition the previous version takes no new Contracts.
 */
class TemplateVersionsTest {

    private static final Duration TRANSITION = Duration.ofDays(30);
    private static final Instant V1_PUBLISHED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant V2_PUBLISHED = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DURING = V2_PUBLISHED.plus(Duration.ofDays(10));
    private static final Instant AFTER = V2_PUBLISHED.plus(Duration.ofDays(31));

    private static final TemplateRef V1 = new TemplateRef("report", 1);
    private static final TemplateRef V2 = new TemplateRef("report", 2);
    private static final TemplateRef V3 = new TemplateRef("report", 3);

    private static TemplateModel template(TemplateRef ref, Instant publishedAt, TemplateStatus status,
                                          int maxPagesUpTo) {
        return new TemplateModel(ref, "Report", null, OutputShape.TEXT, List.of(), List.of(
                new TemplateParameter("max_pages", "Max pages", null, ParameterComparison.MAX,
                        ParameterBounds.numeric(BigDecimal.ONE, BigDecimal.valueOf(maxPagesUpTo)))),
                ExpectationMode.NONE,
                Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                        "NOT_FULFILLED", BigDecimal.ZERO),
                status, publishedAt);
    }

    private static TemplateVersions versions(TemplateModel... models) {
        return TemplateVersions.of(List.of(models), TRANSITION);
    }

    private static final TemplateModel T1 = template(V1, V1_PUBLISHED, TemplateStatus.ACTIVE, 100);
    private static final TemplateModel T2 = template(V2, V2_PUBLISHED, TemplateStatus.ACTIVE, 100);

    private static Contract contract(TemplateRef ref, int maxPages) {
        return new Contract(ref, Map.of("max_pages", new ParameterValue.Number(BigDecimal.valueOf(maxPages))));
    }

    @Test
    void aNewContractUsesTheCurrentVersionWhenAnOfferCoversIt() {
        ContractResolution resolution = versions(T1, T2).resolve(contract(V1, 10), DURING, terms -> true);

        assertThat(resolution.terms().contract().template()).isEqualTo(V2);
        assertThat(resolution.terms().template()).isSameAs(T2);
        assertThat(resolution.terms().contract().params()).isEqualTo(contract(V1, 10).params());
        assertThat(resolution.fallback()).isNull();
    }

    @Test
    void duringTheTransitionAContractFallsBackToThePreviousVersionWhenNothingCoversTheCurrentOne() {
        List<TemplateRef> asked = new ArrayList<>();
        ContractResolution resolution = versions(T1, T2).resolve(contract(V2, 10), DURING, terms -> {
            asked.add(terms.contract().template());
            return terms.contract().template().equals(V1);
        });

        assertThat(asked).containsExactly(V2, V1);
        assertThat(resolution.terms().contract().template()).isEqualTo(V1);
        assertThat(resolution.fallback().current()).isEqualTo(V2);
        assertThat(resolution.fallback().used()).isEqualTo(V1);
        assertThat(resolution.fallback().transitionEndsAt()).isEqualTo(V2_PUBLISHED.plus(TRANSITION));
    }

    @Test
    void whenNoVersionIsCoveredTheCurrentOneIsKeptSoTheNoMatchNamesIt() {
        ContractResolution resolution = versions(T1, T2).resolve(contract(V1, 10), DURING, terms -> false);

        assertThat(resolution.terms().contract().template()).isEqualTo(V2);
        assertThat(resolution.fallback()).isNull();
    }

    @Test
    void afterTheTransitionThePreviousVersionIsNeverFallenBackTo() {
        List<TemplateRef> asked = new ArrayList<>();
        ContractResolution resolution = versions(T1, T2).resolve(contract(V1, 10), AFTER, terms -> {
            asked.add(terms.contract().template());
            return terms.contract().template().equals(V1);
        });

        assertThat(resolution.terms().contract().template()).isEqualTo(V2);
        assertThat(resolution.fallback()).isNull();
        assertThat(asked).doesNotContain(V1);
    }

    @Test
    void aSingleLiveVersionNeedsNoCoverageProbe() {
        ContractResolution resolution = versions(T1).resolve(contract(V1, 10), DURING, terms -> {
            throw new AssertionError("no probe when there is nothing to fall back to");
        });

        assertThat(resolution.terms().contract().template()).isEqualTo(V1);
        assertThat(resolution.fallback()).isNull();
    }

    @Test
    void valuesOutsideTheCurrentVersionsBoundsUseThePreviousVersionDuringTheTransition() {
        TemplateModel narrowerV2 = template(V2, V2_PUBLISHED, TemplateStatus.ACTIVE, 20);

        ContractResolution resolution = versions(T1, narrowerV2).resolve(contract(V1, 50), DURING, terms -> true);

        assertThat(resolution.terms().contract().template()).isEqualTo(V1);
        assertThat(resolution.fallback().current()).isEqualTo(V2);
    }

    @Test
    void valuesOutsideTheCurrentVersionsBoundsAreRefusedAfterTheTransition() {
        TemplateModel narrowerV2 = template(V2, V2_PUBLISHED, TemplateStatus.ACTIVE, 20);

        assertThatThrownBy(() -> versions(T1, narrowerV2).resolve(contract(V1, 50), AFTER, terms -> true))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("max_pages");
    }

    @Test
    void theCurrentVersionIsTheNewestActiveOneAndARetiredVersionIsSkipped() {
        TemplateModel retiredV3 = template(V3, AFTER, TemplateStatus.RETIRED, 100);
        TemplateVersions versions = versions(T1, T2, retiredV3);

        assertThat(versions.lifecycleOf(V2, DURING)).isEqualTo(new TemplateLifecycle(true, true, null));
        assertThat(versions.lifecycleOf(V3, DURING)).isEqualTo(new TemplateLifecycle(false, false, null));
    }

    @Test
    void thePreviousVersionTakesNewContractsUntilTheTransitionEnds() {
        TemplateVersions versions = versions(T1, T2);

        assertThat(versions.lifecycleOf(V1, DURING))
                .isEqualTo(new TemplateLifecycle(false, true, V2_PUBLISHED.plus(TRANSITION)));
        assertThat(versions.lifecycleOf(V1, AFTER)).isEqualTo(new TemplateLifecycle(false, false, null));
        versions.requireTakesNewContracts(V1, DURING);
    }

    @Test
    void afterTheTransitionTheOldVersionTakesNoNewContracts() {
        assertThatThrownBy(() -> versions(T1, T2).requireTakesNewContracts(V1, AFTER))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("report@1 takes no new Contracts")
                .hasMessageContaining("report@2");
    }

    @Test
    void onlyTheVersionBeforeTheCurrentOneIsInTransition() {
        TemplateModel v1 = template(V1, V1_PUBLISHED, TemplateStatus.ACTIVE, 100);
        TemplateModel v2 = template(V2, DURING.minus(Duration.ofDays(1)), TemplateStatus.ACTIVE, 100);
        TemplateModel v3 = template(V3, DURING, TemplateStatus.ACTIVE, 100);

        TemplateVersions versions = versions(v1, v2, v3);

        assertThat(versions.lifecycleOf(V2, DURING).takesNewContracts()).isTrue();
        assertThat(versions.lifecycleOf(V1, DURING).takesNewContracts()).isFalse();
    }

    @Test
    void aRetiredCurrentLineHasNoVersionForNewContracts() {
        TemplateModel retired = template(V1, V1_PUBLISHED, TemplateStatus.RETIRED, 100);

        assertThatThrownBy(() -> versions(retired).resolve(contract(V1, 10), DURING, terms -> true))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("takes no new Contracts");
    }
}
