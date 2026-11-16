package com.hireai.domain.biz.acceptance.check;

import com.hireai.domain.biz.acceptance.check.model.AcceptanceEvidence;
import com.hireai.domain.biz.acceptance.check.model.ExtractedInvoice;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance engine on {@code ap_invoice_extraction@1} (spec S8, D7, D45): the invoice's shape, its
 * own arithmetic under each expectation mode, self-reported anomalies, and the Findings that never count.
 */
class InvoiceExtractionAcceptanceTest {

    private final ValidationDomainServiceImpl engine = new ValidationDomainServiceImpl(new SchemaValidator() {
        @Override
        public JsonCheckResult check(String payload, String schema) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> problemWith(String schema) {
            return Optional.empty();
        }
    });
    private final UUID taskId = UUID.randomUUID();
    private final TaskResultModel result = TaskResultModel.record(taskId, "COMPLETED", "{}", null);

    /** 2 x 50.00 + 1 x 20.00 = 120.00, tax 24.00 at 20 %, total 144.00. */
    private static ExtractedInvoice consistent() {
        return invoice(List.of(line("Widgets", "2", "50.00", "100.00", null), line("Delivery", "1", "20.00", "20.00", null)),
                "120.00", "24.00", "144.00", "20");
    }

    @Test
    void anInvoiceThatAddsUpPassesEveryCheck() {
        ValidationReportModel report = judge(contract(null), consistent(), List.of(), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(rules(report)).containsExactly("INVOICE_JSON:PASS", "INVOICE_FIELDS:PASS",
                "INVOICE_LINE_ARITHMETIC:PASS", "INVOICE_LINES_SUM:PASS", "INVOICE_TAX_ARITHMETIC:PASS",
                "INVOICE_TOTAL:PASS");
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void aResultThatIsNotAJsonObjectIsRejectedAndNothingElseRuns() {
        ValidationReportModel report = engine.judge(contract(null), result, new AcceptanceEvidence(null, null,
                List.of(), FindingFacts.NONE, new InvoiceReading.Unreadable("not JSON")), 1);

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(rules(report)).containsExactly("INVOICE_JSON:REJECT");
    }

    @Test
    void missingOrMalformedFieldsAreRejectedNamingEachOne() {
        ExtractedInvoice broken = new ExtractedInvoice(" ", "GB123", null, "31/12/2026", "pounds", null,
                new BigDecimal("1.00"), null, new BigDecimal("1.00"), null, List.of(), List.of("tax_total"));

        ValidationReportModel report = judge(contract(null), broken, List.of(), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(rules(report)).containsExactly("INVOICE_JSON:PASS", "INVOICE_FIELDS:REJECT");
        assertThat(report.checks().get(1).detail()).contains("supplier_name").contains("invoice_number")
                .contains("invoice_date").contains("currency").contains("line_items").contains("tax_total");
    }

    @Test
    void faithfulToSourceSendsAnUnreportedMismatchToReviewAndReportsTheFinding() {
        // The document itself says 150.00, not 144.00: a faithful extraction copies its mistake.
        ExtractedInvoice wrongTotal = invoice(consistent().lines(), "120.00", "24.00", "150.00", "20");

        ValidationReportModel report = judge(contract(null), wrongTotal, List.of(), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.REVIEW);
        CheckResult total = check(report, "INVOICE_TOTAL");
        assertThat(total.sendsToReview()).isTrue();
        assertThat(total.detail()).contains("144.00").contains("150.00").contains("did not report");
        assertThat(report.findings()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("INVOICE_ARITHMETIC");
            assertThat(f.detail()).contains("150.00");
        });
    }

    @Test
    void aReportedMismatchGoesToReviewWithTheAgentsWordsInEitherMode() {
        ExtractedInvoice wrongTotal = invoice(consistent().lines(), "120.00", "24.00", "150.00", "20");
        List<SelfReportedAnomaly> reported = List.of(new SelfReportedAnomaly("INVOICE_TOTAL",
                "The invoice's total is 150.00 although its lines and tax come to 144.00"));

        for (String mode : List.of("faithful_to_source", "business_valid")) {
            ValidationReportModel report = judge(contract(mode), wrongTotal, reported, FindingFacts.NONE);

            assertThat(report.verdict()).as(mode).isEqualTo(Verdict.REVIEW);
            assertThat(check(report, "INVOICE_TOTAL").detail()).as(mode).contains("The Agent reported");
            assertThat(report.anomalies()).hasSize(1);
        }
    }

    @Test
    void businessValidRejectsAnUnreportedMismatchForRework() {
        ExtractedInvoice wrongSum = invoice(List.of(line("Widgets", "2", "50.00", "100.00", null)),
                "120.00", "24.00", "144.00", "20");

        ValidationReportModel report = judge(contract("business_valid"), wrongSum, List.of(), FindingFacts.NONE);

        assertThat(report.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(report.rejectingChecks()).extracting(CheckResult::rule).containsExactly("INVOICE_LINES_SUM");
    }

    @Test
    void eachLineAndThePerLineTaxAreCheckedWithinTheTolerance() {
        ExtractedInvoice lines = invoice(List.of(line("Widgets", "3", "33.33", "100.00", "20.00"),
                line("Delivery", null, null, "20.00", "4.00")), "120.00", "24.00", "144.00", null);

        ValidationReportModel loose = judge(contractWithTolerance("0.01"), lines, List.of(), FindingFacts.NONE);
        ValidationReportModel wide = judge(contractWithTolerance("0.05"), lines, List.of(), FindingFacts.NONE);

        // 3 x 33.33 = 99.99: one cent off, at the default tolerance, then outside a 0.00 one.
        assertThat(check(loose, "INVOICE_LINE_ARITHMETIC").passed()).isTrue();
        assertThat(check(wide, "INVOICE_TAX_ARITHMETIC").passed()).isTrue();
        assertThat(check(judge(contractWithTolerance("0"), lines, List.of(), FindingFacts.NONE),
                "INVOICE_LINE_ARITHMETIC").sendsToReview()).isTrue();
    }

    @Test
    void duplicatesAreFindingsAndNeverChangeTheVerdict() {
        UUID earlierTask = UUID.randomUUID();
        FindingFacts facts = new FindingFacts(List.of(), List.of(new FindingFacts.ItemRef(earlierTask, 4)),
                List.of(new FindingFacts.ItemRef(taskId, 1)));

        ValidationReportModel report = judge(contract(null), consistent(), List.of(), facts);

        assertThat(report.verdict()).isEqualTo(Verdict.PASS);
        assertThat(report.findings()).extracting(f -> f.code()).containsExactly("DUPLICATE_DOCUMENT", "DUPLICATE_INVOICE");
        assertThat(report.findings().get(0).detail()).contains(earlierTask.toString()).contains("Item 4");
        assertThat(report.findings().get(1).detail()).contains("Item 1 of this task");
    }

    @Test
    void theDuplicateKeyIgnoresCaseAndPunctuation() {
        ExtractedInvoice a = new ExtractedInvoice("A", "GB 123-456", "inv-0042", "2026-11-02", "GBP", null,
                null, null, null, null, null, null);
        ExtractedInvoice b = new ExtractedInvoice("A", "gb123456", "INV0042", "2026-11-02", "GBP", null,
                null, null, null, null, null, null);

        assertThat(a.duplicateKey()).isPresent().isEqualTo(b.duplicateKey());
    }

    @Test
    void theSeededListsMatchTheTemplate() {
        assertThat(InvoiceExtractionTemplate.ANOMALY_ELIGIBLE).containsExactlyInAnyOrder("INVOICE_LINE_ARITHMETIC",
                "INVOICE_LINES_SUM", "INVOICE_TAX_ARITHMETIC", "INVOICE_TOTAL");
        assertThat(engine.needsInvoice(contract(null))).isTrue();
        assertThat(engine.needsFindingFacts(contract(null))).isTrue();
    }

    // ---- helpers ----

    private ValidationReportModel judge(Contract contract, ExtractedInvoice invoice, List<SelfReportedAnomaly> anomalies,
                                        FindingFacts facts) {
        return engine.judge(contract, result, new AcceptanceEvidence(null, null, anomalies, facts,
                new InvoiceReading.Read(invoice)), 1);
    }

    private static Contract contract(String expectation) {
        Map<String, ParameterValue> params = new HashMap<>();
        if (expectation != null) {
            params.put("expectation", new ParameterValue.Text(expectation));
        }
        return new Contract(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1, params);
    }

    private static Contract contractWithTolerance(String tolerance) {
        return new Contract(InvoiceExtractionTemplate.AP_INVOICE_EXTRACTION_V1,
                Map.of("amount_tolerance", new ParameterValue.Number(new BigDecimal(tolerance))));
    }

    private static ExtractedInvoice invoice(List<ExtractedInvoice.Line> lines, String subtotal, String tax,
                                            String total, String rate) {
        return new ExtractedInvoice("Acme Supplies Ltd", "GB123456789", "INV-1001", "2026-11-02", "GBP", "PO-77",
                new BigDecimal(subtotal), new BigDecimal(tax), new BigDecimal(total),
                rate == null ? null : new BigDecimal(rate), lines, List.of());
    }

    private static ExtractedInvoice.Line line(String description, String qty, String price, String amount, String tax) {
        return new ExtractedInvoice.Line(description, qty == null ? null : new BigDecimal(qty),
                price == null ? null : new BigDecimal(price), new BigDecimal(amount),
                tax == null ? null : new BigDecimal(tax));
    }

    private static CheckResult check(ValidationReportModel report, String rule) {
        return report.checks().stream().filter(c -> c.rule().equals(rule)).findFirst().orElseThrow();
    }

    private static List<String> rules(ValidationReportModel report) {
        return report.checks().stream().map(c -> c.rule() + ":" + c.outcome()).toList();
    }
}
