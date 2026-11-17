package com.hireai.domain.biz.acceptance.template.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.enums.ExpectationMode;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.acceptance.template.enums.ParameterComparison;
import com.hireai.domain.biz.acceptance.template.enums.TemplateStatus;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Template's parameters declare bounds and one comparison; a Contract's values and an Offer's
 * ranges must stay inside those bounds (ADR 0009, spec D34).
 */
class TemplateModelTest {

    private static final TemplateRef REF = new TemplateRef("demo_work", 1);

    private static final TemplateModel TEMPLATE = template(TemplateStatus.ACTIVE, List.of(
            new TemplateParameter("max_pages", "Max pages", "Upper page count", ParameterComparison.MAX,
                    ParameterBounds.numeric(new BigDecimal("1"), new BigDecimal("100"))),
            new TemplateParameter("min_words", "Min words", "Lower word count", ParameterComparison.MIN,
                    ParameterBounds.numeric(new BigDecimal("0"), new BigDecimal("5000"))),
            new TemplateParameter("languages", "Languages", "Languages allowed", ParameterComparison.SUBSET,
                    ParameterBounds.closedSet(List.of("en", "fr", "de"))),
            new TemplateParameter("tone", "Tone", "One tone", ParameterComparison.ENUM,
                    ParameterBounds.closedSet(List.of("formal", "casual"))),
            new TemplateParameter("notes", "Notes", "Free value", ParameterComparison.ENUM,
                    ParameterBounds.openText(10)),
            new TemplateParameter("cite_sources", "Cite sources", "Flag", ParameterComparison.FLAG,
                    ParameterBounds.none())));

    @Test
    void aContractInsideEveryBoundIsValid() {
        Contract contract = new Contract(REF, Map.of(
                "max_pages", new ParameterValue.Number(new BigDecimal("100")),
                "min_words", new ParameterValue.Number(BigDecimal.ZERO),
                "languages", new ParameterValue.TextSet(Set.of("en", "de")),
                "tone", new ParameterValue.Text("formal"),
                "notes", new ParameterValue.Text("short"),
                "cite_sources", new ParameterValue.Flag(true)));

        assertThatCode(() -> TEMPLATE.requireValid(contract)).doesNotThrowAnyException();
    }

    @Test
    void aContractMayLeaveParametersUnset() {
        assertThatCode(() -> TEMPLATE.requireValid(new Contract(REF, Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void aNumberOutsideItsBoundsIsRejected() {
        assertRejected(Map.of("max_pages", new ParameterValue.Number(new BigDecimal("101"))), "max_pages");
        assertRejected(Map.of("min_words", new ParameterValue.Number(new BigDecimal("-1"))), "min_words");
    }

    @Test
    void aSetMemberOutsideTheAllowedValuesIsRejected() {
        assertRejected(Map.of("languages", new ParameterValue.TextSet(Set.of("en", "jp"))), "languages");
    }

    @Test
    void anEnumValueMustBeAllowedOrWithinItsLength() {
        assertRejected(Map.of("tone", new ParameterValue.Text("sarcastic")), "tone");
        assertRejected(Map.of("notes", new ParameterValue.Text("far too long a note")), "notes");
    }

    @Test
    void aValueOfTheWrongKindIsRejected() {
        assertRejected(Map.of("cite_sources", new ParameterValue.Text("yes")), "cite_sources");
        assertRejected(Map.of("max_pages", new ParameterValue.Flag(true)), "max_pages");
    }

    @Test
    void anUnknownParameterIsRejected() {
        assertRejected(Map.of("colour", new ParameterValue.Text("red")), "colour");
    }

    @Test
    void aContractNamingAnotherTemplateVersionIsRejected() {
        Contract other = new Contract(new TemplateRef("demo_work", 2), Map.of());
        assertThatThrownBy(() -> TEMPLATE.requireValid(other))
                .isInstanceOf(DomainException.class).hasMessageContaining("demo_work@2");
    }

    @Test
    void aRetiredTemplateTakesNoNewContract() {
        TemplateModel retired = template(TemplateStatus.RETIRED, List.of());
        assertThatThrownBy(() -> retired.requireValid(new Contract(REF, Map.of())))
                .isInstanceOf(DomainException.class).hasMessageContaining("retired");
    }

    @Test
    void anOfferRangeInsideTheBoundsIsValid() {
        OfferTerm term = new OfferTerm(REF, Map.of(
                "max_pages", new ParameterRange.Bound(new BigDecimal("40")),
                "languages", new ParameterRange.OneOf(Set.of("en", "fr")),
                "tone", new ParameterRange.AnyValue(),
                "notes", new ParameterRange.AnyValue(),
                "cite_sources", new ParameterRange.Flag(false)));

        assertThatCode(() -> TEMPLATE.requireValid(term)).doesNotThrowAnyException();
    }

    @Test
    void anOfferRangeOutsideTheBoundsIsRejected() {
        assertThatThrownBy(() -> TEMPLATE.requireValid(new OfferTerm(REF,
                Map.of("max_pages", new ParameterRange.Bound(new BigDecimal("500"))))))
                .isInstanceOf(DomainException.class).hasMessageContaining("max_pages");
        assertThatThrownBy(() -> TEMPLATE.requireValid(new OfferTerm(REF,
                Map.of("tone", new ParameterRange.OneOf(Set.of("angry"))))))
                .isInstanceOf(DomainException.class).hasMessageContaining("tone");
        assertThatThrownBy(() -> TEMPLATE.requireValid(new OfferTerm(REF,
                Map.of("max_pages", new ParameterRange.AnyValue()))))
                .isInstanceOf(DomainException.class).hasMessageContaining("max_pages");
    }

    @Test
    void anOfferRangeOutsideTheBoundsSaysWhichTemplateAndWhatTheBoundsAllow() {
        assertOfferRejected(Map.of("max_pages", new ParameterRange.Bound(new BigDecimal("500"))),
                "demo_work@1", "Max pages (max_pages)", "between 1 and 100", "500");
        assertOfferRejected(Map.of("tone", new ParameterRange.OneOf(Set.of("angry"))),
                "Tone (tone)", "formal, casual", "angry");
        assertOfferRejected(Map.of("notes", new ParameterRange.OneOf(Set.of("far too long a note"))),
                "Notes (notes)", "at most 10 characters");
        assertOfferRejected(Map.of("max_pages", new ParameterRange.AnyValue()),
                "Max pages (max_pages)", "needs a single number");
        assertOfferRejected(Map.of("cite_sources", new ParameterRange.Bound(BigDecimal.ONE)),
                "Cite sources (cite_sources)", "yes or no");
        assertOfferRejected(Map.of("tone", new ParameterRange.Bound(BigDecimal.ONE)),
                "Tone (tone)", "any value or a list of values");
        assertOfferRejected(Map.of("colour", new ParameterRange.AnyValue()),
                "demo_work@1 has no parameter colour", "max_pages", "cite_sources");
    }

    @Test
    void aParameterMustDeclareBoundsThatFitItsComparison() {
        assertThatThrownBy(() -> new TemplateParameter("languages", "L", "d", ParameterComparison.SUBSET,
                ParameterBounds.numeric(BigDecimal.ONE, BigDecimal.TEN)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TemplateParameter("max_pages", "M", "d", ParameterComparison.MAX,
                ParameterBounds.numeric(BigDecimal.TEN, BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TemplateParameter("flag", "F", "d", ParameterComparison.FLAG,
                ParameterBounds.openText(5)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void aRulingSettlementMustNameEachRulingWithAFractionOfThePrice() {
        assertThatThrownBy(() -> new TemplateModel(REF, "T", "d", OutputShape.TEXT, List.of(), List.of(),
                ExpectationMode.NONE, Map.of("FULFILLED", BigDecimal.ONE), TemplateStatus.ACTIVE, Instant.EPOCH))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new TemplateModel(REF, "T", "d", OutputShape.TEXT, List.of(), List.of(),
                ExpectationMode.NONE, Map.of("FULFILLED", new BigDecimal("1.5"),
                "PARTIALLY_FULFILLED", BigDecimal.ONE, "NOT_FULFILLED", BigDecimal.ZERO),
                TemplateStatus.ACTIVE, Instant.EPOCH))
                .isInstanceOf(DomainException.class);
    }

    private static void assertRejected(Map<String, ParameterValue> params, String parameter) {
        assertThatThrownBy(() -> TEMPLATE.requireValid(new Contract(REF, params)))
                .isInstanceOf(DomainException.class).hasMessageContaining(parameter);
    }

    private static void assertOfferRejected(Map<String, ParameterRange> params, String... phrases) {
        assertThatThrownBy(() -> TEMPLATE.requireValid(new OfferTerm(REF, params)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    for (String phrase : phrases) {
                        org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains(phrase);
                    }
                });
    }

    private static TemplateModel template(TemplateStatus status, List<TemplateParameter> parameters) {
        return new TemplateModel(REF, "Demo work", "A demo template", OutputShape.TEXT,
                List.of("FORMAT_TEXT_NON_EMPTY"), parameters, ExpectationMode.NONE,
                Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED", new BigDecimal("0.5"),
                        "NOT_FULFILLED", BigDecimal.ZERO),
                status, Instant.EPOCH);
    }
}
