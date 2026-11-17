package com.hireai.domain.biz.acceptance.coverage.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.coverage.info.Coverage;
import com.hireai.domain.biz.acceptance.coverage.service.impl.OfferCoverageDomainServiceImpl;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.domain.biz.acceptance.template.model.ParameterBounds;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateParameter;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offer coverage (spec D34): an Offer covers a Contract when it names the Contract's Template version
 * and every parameter the Contract sets passes its comparison against the Offer's range.
 */
class OfferCoverageDomainServiceTest {

    private static final TemplateRef REF = new TemplateRef("demo_work", 1);
    private static final TemplateRef OTHER_VERSION = new TemplateRef("demo_work", 2);

    private static final TemplateModel TEMPLATE = new TemplateModel(REF, "Demo", "A demo template",
            OutputShape.TEXT, List.of(), List.of(
            new TemplateParameter("max_pages", "Max pages", "Upper page count", ParameterComparison.MAX,
                    ParameterBounds.numeric(new BigDecimal("1"), new BigDecimal("100"))),
            new TemplateParameter("min_words", "Min words", "Lower word count", ParameterComparison.MIN,
                    ParameterBounds.numeric(new BigDecimal("0"), new BigDecimal("5000"))),
            new TemplateParameter("languages", "Languages", "Languages allowed", ParameterComparison.SUBSET,
                    ParameterBounds.closedSet(List.of("en", "fr", "de"))),
            new TemplateParameter("tone", "Tone", "One tone", ParameterComparison.ENUM,
                    ParameterBounds.closedSet(List.of("formal", "casual"))),
            new TemplateParameter("cite_sources", "Cite sources", "Flag", ParameterComparison.FLAG,
                    ParameterBounds.none())),
            ExpectationMode.NONE,
            Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                    "NOT_FULFILLED", BigDecimal.ZERO),
            TemplateStatus.ACTIVE, Instant.parse("2026-11-15T00:00:00Z"));

    private final OfferCoverageDomainService coverage = new OfferCoverageDomainServiceImpl();

    private static Contract contract(Map<String, ParameterValue> params) {
        return new Contract(REF, params);
    }

    private static Offer offer(Map<String, ParameterRange> ranges) {
        return Offer.of(new OfferTerm(REF, ranges));
    }

    private static ParameterValue.Number number(String value) {
        return new ParameterValue.Number(new BigDecimal(value));
    }

    private static ParameterRange.Bound bound(String value) {
        return new ParameterRange.Bound(new BigDecimal(value));
    }

    private Coverage check(Map<String, ParameterValue> params, Map<String, ParameterRange> ranges) {
        return coverage.coverage(TEMPLATE, contract(params), offer(ranges));
    }

    @Test
    void maxIsCoveredUpToTheOffersBound() {
        assertThat(check(Map.of("max_pages", number("10")), Map.of("max_pages", bound("10"))).covered()).isTrue();
        assertThat(check(Map.of("max_pages", number("11")), Map.of("max_pages", bound("10"))).blockingParameters())
                .containsExactly("max_pages");
    }

    @Test
    void minIsCoveredDownToTheOffersBound() {
        assertThat(check(Map.of("min_words", number("300")), Map.of("min_words", bound("300"))).covered()).isTrue();
        assertThat(check(Map.of("min_words", number("299")), Map.of("min_words", bound("300"))).covered()).isFalse();
    }

    @Test
    void subsetIsCoveredWhenEveryValueIsOffered() {
        Map<String, ParameterRange> enFr = Map.of("languages", new ParameterRange.OneOf(Set.of("en", "fr")));

        assertThat(check(Map.of("languages", new ParameterValue.TextSet(Set.of("fr"))), enFr).covered()).isTrue();
        assertThat(check(Map.of("languages", new ParameterValue.TextSet(Set.of("fr", "de"))), enFr).covered())
                .isFalse();
        assertThat(check(Map.of("languages", new ParameterValue.TextSet(Set.of("de"))),
                Map.of("languages", new ParameterRange.AnyValue())).covered()).isTrue();
    }

    @Test
    void enumIsCoveredWhenTheValueIsOffered() {
        Map<String, ParameterRange> formal = Map.of("tone", new ParameterRange.OneOf(Set.of("formal")));

        assertThat(check(Map.of("tone", new ParameterValue.Text("formal")), formal).covered()).isTrue();
        assertThat(check(Map.of("tone", new ParameterValue.Text("casual")), formal).covered()).isFalse();
        assertThat(check(Map.of("tone", new ParameterValue.Text("casual")),
                Map.of("tone", new ParameterRange.AnyValue())).covered()).isTrue();
    }

    @Test
    void aSetFlagNeedsAnOfferThatAcceptsIt() {
        Map<String, ParameterValue> cite = Map.of("cite_sources", new ParameterValue.Flag(true));

        assertThat(check(cite, Map.of("cite_sources", new ParameterRange.Flag(true))).covered()).isTrue();
        assertThat(check(cite, Map.of("cite_sources", new ParameterRange.Flag(false))).covered()).isFalse();
        assertThat(check(Map.of("cite_sources", new ParameterValue.Flag(false)),
                Map.of("cite_sources", new ParameterRange.Flag(false))).covered()).isTrue();
    }

    @Test
    void aParameterTheContractLeavesUnsetIsAlwaysCovered() {
        assertThat(check(Map.of(), Map.of("max_pages", bound("1"))).covered()).isTrue();
    }

    @Test
    void anOfferNeverHoldsAnAgentToAParameterItDidNotName() {
        assertThat(check(Map.of("max_pages", number("100")), Map.of()).blockingParameters())
                .containsExactly("max_pages");
        assertThat(check(Map.of("cite_sources", new ParameterValue.Flag(true)), Map.of()).covered()).isFalse();
        assertThat(check(Map.of("cite_sources", new ParameterValue.Flag(false)), Map.of()).covered())
                .as("a false flag asks nothing of the Agent, like an unset one")
                .isTrue();
    }

    @Test
    void everyFailingParameterIsReportedInNameOrder() {
        Coverage result = check(
                Map.of("tone", new ParameterValue.Text("casual"), "max_pages", number("50"),
                        "min_words", number("10")),
                Map.of("tone", new ParameterRange.OneOf(Set.of("formal")), "max_pages", bound("20"),
                        "min_words", bound("0")));

        assertThat(result.templateOffered()).isTrue();
        assertThat(result.blockingParameters()).containsExactly("max_pages", "tone");
    }

    @Test
    void anOfferCoversOnlyTheTemplateVersionsItNames() {
        Offer otherVersion = Offer.of(new OfferTerm(OTHER_VERSION, Map.of()));

        Coverage result = coverage.coverage(TEMPLATE, contract(Map.of()), otherVersion);

        assertThat(result.covered()).isFalse();
        assertThat(result.templateOffered()).isFalse();
    }

    @Test
    void theMatchingTermIsChosenAmongSeveral() {
        Offer offer = Offer.of(new OfferTerm(OTHER_VERSION, Map.of()),
                new OfferTerm(REF, Map.of("max_pages", bound("30"))));

        assertThat(coverage.coverage(TEMPLATE, contract(Map.of("max_pages", number("30"))), offer).covered())
                .isTrue();
    }
}
