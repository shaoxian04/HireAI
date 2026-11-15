package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
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

    private static final String LINK = "https://cdn.example.com/x.png";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    private static Contract file(Map<String, ParameterValue> params) {
        return new Contract(GenericTemplates.FILE_BASIC_V1, params);
    }

    private DeliveryOutcome captured(byte[] content, String contentType) {
        return new DeliveryOutcome.Captured(DeliverySnapshot.capture(taskId, UUID.randomUUID(), LINK,
                new DeliveryFetch.Fetched(content, contentType), java.time.Instant.parse("2026-11-15T10:00:00Z")));
    }

    private ValidationReportModel validateFile(Contract contract, String url, DeliveryOutcome delivery) {
        return svc.validate(contract, TaskResultModel.record(taskId, "COMPLETED", "{}", url), delivery, 1);
    }

    @Test
    void fileBasicNeedsAnHttpsLinkToAFetchedFile() {
        assertThat(validateFile(file(Map.of()), LINK, captured(PNG, "image/png")).checks())
                .extracting(CheckResult::rule, CheckResult::passed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("FORMAT_FILE_HTTPS_URL", true),
                        org.assertj.core.groups.Tuple.tuple("FILE_FETCHED", true));
        assertThat(validateFile(file(Map.of()), "http://cdn.example.com/x.pdf",
                new DeliveryOutcome.Unavailable("not fetched")).checks())
                .extracting(CheckResult::rule).containsExactly("FORMAT_FILE_HTTPS_URL");
    }

    @Test
    void aFileThatCouldNotBeFetchedFailsWithTheReason() {
        ValidationReportModel report = validateFile(file(Map.of()), LINK,
                new DeliveryOutcome.Unavailable("the link answered 404"));
        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.checks()).filteredOn(c -> c.rule().equals("FILE_FETCHED"))
                .singleElement().satisfies(c -> assertThat(c.detail()).contains("404"));
    }

    @Test
    void anEmptyFileIsNotADelivery() {
        assertThat(validateFile(file(Map.of()), LINK, captured(new byte[0], "image/png")).verdict())
                .isEqualTo(Verdict.FAIL);
    }

    @Test
    void theMediaTypeCheckRunsOnlyWhenTheContractNamesTypesAndJudgesTheBytes() {
        Contract pngOnly = file(Map.of(GenericTemplates.MEDIA_TYPES, new ParameterValue.TextSet(Set.of("image/png"))));
        assertThat(validateFile(pngOnly, LINK, captured(PNG, "application/octet-stream")).verdict())
                .isEqualTo(Verdict.PASS);
        ValidationReportModel html = validateFile(pngOnly, LINK,
                captured("<!doctype html><p>hi</p>".getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/png"));
        assertThat(html.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(html.checks()).filteredOn(c -> c.rule().equals("FILE_MEDIA_TYPE")).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("text/html").contains("image/png"));
        assertThat(validateFile(file(Map.of()), LINK, captured(PNG, "image/png")).checks())
                .extracting(CheckResult::rule).doesNotContain("FILE_MEDIA_TYPE", "FILE_MAX_BYTES");
    }

    @Test
    void theSizeCheckHoldsTheFileToTheContractsLimit() {
        Contract small = file(Map.of(GenericTemplates.MAX_BYTES, n(12)));
        assertThat(validateFile(small, LINK, captured(PNG, "image/png")).verdict()).isEqualTo(Verdict.PASS);
        ValidationReportModel tooBig = validateFile(small, LINK, new DeliveryOutcome.TooLarge(12));
        assertThat(tooBig.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(tooBig.checks()).extracting(CheckResult::rule, CheckResult::passed)
                .contains(org.assertj.core.groups.Tuple.tuple("FILE_MAX_BYTES", false));
    }

    @Test
    void aFileOverThePlatformLimitFailsTheFetchWhenTheContractSetsNoLimit() {
        ValidationReportModel report = validateFile(file(Map.of()), LINK, new DeliveryOutcome.TooLarge(1000));
        assertThat(report.checks()).extracting(CheckResult::rule, CheckResult::passed)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("FORMAT_FILE_HTTPS_URL", true),
                        org.assertj.core.groups.Tuple.tuple("FILE_FETCHED", false));
    }

    @Test
    void theFetchLimitIsTheContractsLimitCappedByThePlatforms() {
        assertThat(svc.deliveryByteLimit(file(Map.of(GenericTemplates.MAX_BYTES, n(12))), 1000)).isEqualTo(12);
        assertThat(svc.deliveryByteLimit(file(Map.of(GenericTemplates.MAX_BYTES, n(5000))), 1000)).isEqualTo(1000);
        assertThat(svc.deliveryByteLimit(file(Map.of()), 1000)).isEqualTo(1000);
    }

    @Test
    void onlyAFileContractWithAnHttpsLinkNeedsTheDeliverableFetched() {
        assertThat(svc.needsDelivery(file(Map.of()), TaskResultModel.record(taskId, "COMPLETED", "{}", LINK))).isTrue();
        assertThat(svc.needsDelivery(file(Map.of()), TaskResultModel.record(taskId, "COMPLETED", "{}", "http://x.example/a"))).isFalse();
        assertThat(svc.needsDelivery(text(Map.of()), TaskResultModel.record(taskId, "COMPLETED", "hi", LINK))).isFalse();
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
