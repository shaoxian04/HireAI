package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.offer.model.Offer;
import com.hireai.domain.biz.acceptance.offer.model.OfferTerm;
import com.hireai.domain.biz.acceptance.offer.model.ParameterRange;
import com.hireai.domain.biz.acceptance.template.model.GenericTemplates;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Automated acceptance judges the frozen Contract (#90): text_basic, custom_json and file_basic. */
class ContractValidationTest {

    /** "NOT_A_SCHEMA" is the one schema the fake cannot read; "SCHEMA_BAD" never matches. */
    private final SchemaValidator fake = new SchemaValidator() {
        @Override
        public JsonCheckResult check(String payload, String schema) {
            boolean valid = payload != null && payload.trim().startsWith("{") && payload.trim().endsWith("}");
            if (!valid) return new JsonCheckResult(false, false, false, "bad json");
            if (schema == null || schema.isBlank()) return new JsonCheckResult(true, false, false, "no schema");
            if ("SCHEMA_BAD".equals(schema)) return new JsonCheckResult(true, true, false, "mismatch");
            return new JsonCheckResult(true, true, true, "ok");
        }

        @Override
        public Optional<String> problemWith(String schema) {
            return "NOT_A_SCHEMA".equals(schema) ? Optional.of("it is not JSON") : Optional.empty();
        }
    };
    private final ValidationDomainService svc = new ValidationDomainServiceImpl(fake);
    private final UUID taskId = UUID.randomUUID();

    private ValidationReportModel validate(Contract contract, String payload, String url) {
        return svc.validate(contract, TaskResultModel.record(taskId, "COMPLETED", payload, url), 1);
    }

    private static Contract text(Map<String, ParameterValue> params) {
        return new Contract(GenericTemplates.TEXT_BASIC_V1, params);
    }

    private static Contract json(String schema) {
        return new Contract(GenericTemplates.CUSTOM_JSON_V1, schema == null ? Map.of()
                : Map.of(GenericTemplates.SCHEMA, new ParameterValue.Text(schema)));
    }

    private static ParameterValue.Number n(int v) {
        return new ParameterValue.Number(BigDecimal.valueOf(v));
    }

    @Test
    void textBasicPassesNonBlankTextAndFailsBlank() {
        assertThat(validate(text(Map.of()), "a summary", null).verdict()).isEqualTo(Verdict.PASS);
        assertThat(validate(text(Map.of()), "  ", null).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void textBasicWithoutLengthBoundsRunsOnlyTheNonEmptyCheck() {
        assertThat(validate(text(Map.of()), "a summary", null).checks())
                .extracting(CheckResult::rule).containsExactly("FORMAT_TEXT_NON_EMPTY");
    }

    @Test
    void textBasicHoldsTheTextWithinItsLengthBounds() {
        Contract bounded = text(Map.of(GenericTemplates.MIN_LENGTH, n(3), GenericTemplates.MAX_LENGTH, n(5)));

        assertThat(validate(bounded, "abc", null).verdict()).isEqualTo(Verdict.PASS);
        assertThat(validate(bounded, "abcde", null).verdict()).isEqualTo(Verdict.PASS);
        ValidationReportModel tooShort = validate(bounded, "ab", null);
        assertThat(tooShort.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(tooShort.checks()).filteredOn(c -> !c.passed())
                .extracting(CheckResult::rule).containsExactly("TEXT_MIN_LENGTH");
        ValidationReportModel tooLong = validate(bounded, "abcdef", null);
        assertThat(tooLong.checks()).filteredOn(c -> !c.passed())
                .extracting(CheckResult::rule).containsExactly("TEXT_MAX_LENGTH");
    }

    @Test
    void textLengthCountsCharactersNotUtf16Units() {
        Contract atMostTwo = text(Map.of(GenericTemplates.MAX_LENGTH, n(2)));
        // Two emoji: four UTF-16 units, two characters.
        assertThat(validate(atMostTwo, "😀😀", null).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void customJsonChecksParseAndSchemaMatch() {
        assertThat(validate(json("SCHEMA_OK"), "{\"a\":1}", null).checks())
                .extracting(CheckResult::rule).containsExactly("FORMAT_JSON_PARSEABLE", "SCHEMA_MATCH");
        assertThat(validate(json("SCHEMA_OK"), "{\"a\":1}", null).verdict()).isEqualTo(Verdict.PASS);
        assertThat(validate(json("SCHEMA_BAD"), "{\"a\":1}", null).verdict()).isEqualTo(Verdict.FAIL);
        assertThat(validate(json("SCHEMA_OK"), "not json", null).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void customJsonWithoutASchemaOnlyNeedsToParse() {
        assertThat(validate(json(null), "{\"a\":1}", null).verdict()).isEqualTo(Verdict.PASS);
        assertThat(validate(json(null), "nope", null).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void fileBasicNeedsAnHttpsLink() {
        Contract file = new Contract(GenericTemplates.FILE_BASIC_V1, Map.of());
        assertThat(validate(file, "{}", "https://cdn.example.com/x.pdf").verdict()).isEqualTo(Verdict.PASS);
        assertThat(validate(file, "{}", "http://cdn.example.com/x.pdf").verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void aContractWithAnUnreadableSchemaCannotBeChecked() {
        assertThatThrownBy(() -> svc.requireCheckable(json("NOT_A_SCHEMA")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("not a valid JSON Schema")
                .hasMessageContaining("it is not JSON");
    }

    @Test
    void aContractWithAReadableOrNoSchemaCanBeChecked() {
        assertThatCode(() -> svc.requireCheckable(json("SCHEMA_OK"))).doesNotThrowAnyException();
        assertThatCode(() -> svc.requireCheckable(json(null))).doesNotThrowAnyException();
        assertThatCode(() -> svc.requireCheckable(text(Map.of()))).doesNotThrowAnyException();
    }

    @Test
    void aTextContractWhoseMinimumExceedsItsMaximumCannotBeChecked() {
        Contract impossible = text(Map.of(GenericTemplates.MIN_LENGTH, n(10), GenericTemplates.MAX_LENGTH, n(5)));
        assertThatThrownBy(() -> svc.requireCheckable(impossible))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("min_length");
    }

    @Test
    void anOfferListingAnUnreadableSchemaCannotBeChecked() {
        Offer offer = Offer.of(new OfferTerm(GenericTemplates.CUSTOM_JSON_V1,
                Map.of(GenericTemplates.SCHEMA, new ParameterRange.OneOf(Set.of("SCHEMA_OK", "NOT_A_SCHEMA")))));
        assertThatThrownBy(() -> svc.requireCheckable(offer))
                .isInstanceOf(DomainException.class)
                .hasMessageStartingWith("outputSpec.schema: ")
                .hasMessageContaining("it is not JSON");
    }

    @Test
    void anOfferAcceptingAnySchemaOrReadableOnesCanBeChecked() {
        assertThatCode(() -> svc.requireCheckable(Offer.of(new OfferTerm(GenericTemplates.CUSTOM_JSON_V1,
                Map.of(GenericTemplates.SCHEMA, new ParameterRange.AnyValue()))))).doesNotThrowAnyException();
        assertThatCode(() -> svc.requireCheckable(Offer.of(new OfferTerm(GenericTemplates.CUSTOM_JSON_V1,
                Map.of(GenericTemplates.SCHEMA, new ParameterRange.OneOf(Set.of("SCHEMA_OK")))))))
                .doesNotThrowAnyException();
    }
}
