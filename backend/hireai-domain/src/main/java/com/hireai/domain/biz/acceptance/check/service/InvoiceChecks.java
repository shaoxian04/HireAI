package com.hireai.domain.biz.acceptance.check.service;

import com.hireai.domain.biz.acceptance.check.model.ExtractedInvoice;
import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.contract.model.ParameterValue;
import com.hireai.domain.biz.adjudication.model.CheckResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.AMOUNT_TOLERANCE;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.BUSINESS_VALID;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.DEFAULT_AMOUNT_TOLERANCE;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.DUPLICATE_DOCUMENT;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.DUPLICATE_INVOICE;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.EXPECTATION;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_ARITHMETIC;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_FIELDS;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_LINES_SUM;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_LINE_ARITHMETIC;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_TAX_ARITHMETIC;
import static com.hireai.domain.biz.acceptance.template.model.InvoiceExtractionTemplate.INVOICE_TOTAL;

/**
 * The {@code ap_invoice_extraction} entries of the {@link CheckLibrary} (spec S8, D7, D45). They read only
 * the delivered invoice and its Contract, never the source document: the fields must be there and well
 * formed (else rework), and the invoice must agree with itself. How a disagreement is judged follows the
 * Contract's expectation mode:
 * <ul>
 *   <li><b>faithful to source</b> (the default): the extraction copies the document, so a mismatch may be
 *       the supplier's own mistake. It always goes to the buyer's review, never to rework, whether or not
 *       the Agent reported it (an unreported mismatch is forced to review, D7);</li>
 *   <li><b>business-valid</b>: the result must add up, so an unreported mismatch is rejected for rework;
 *       one the Agent reported goes to review instead (mandatory anomaly reporting).</li>
 * </ul>
 */
final class InvoiceChecks {

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MONEY_SCALE = 2;

    private InvoiceChecks() {
    }

    static Optional<CheckResult> fields(ExtractedInvoice invoice) {
        if (invoice == null) {
            return Optional.empty();
        }
        List<String> problems = new ArrayList<>();
        for (String wrongType : invoice.typeProblems()) {
            problems.add(wrongType.endsWith(" (out of range)")
                    ? wrongType.substring(0, wrongType.length() - " (out of range)".length())
                    + " is out of range for an invoice amount"
                    : wrongType + " has the wrong type");
        }
        requireText(problems, "supplier_name", invoice.supplierName());
        requireText(problems, "supplier_tax_id", invoice.supplierTaxId());
        requireText(problems, "invoice_number", invoice.invoiceNumber());
        if (blank(invoice.invoiceDate())) {
            problems.add("invoice_date is missing");
        } else if (!isIsoDate(invoice.invoiceDate())) {
            problems.add("invoice_date must be a date written YYYY-MM-DD; got " + invoice.invoiceDate());
        }
        if (blank(invoice.currency())) {
            problems.add("currency is missing");
        } else if (!CURRENCY.matcher(invoice.currency()).matches()) {
            problems.add("currency must be a three-letter ISO 4217 code such as GBP; got " + invoice.currency());
        }
        requireAmount(problems, "subtotal", invoice.subtotal());
        requireAmount(problems, "tax_total", invoice.taxTotal());
        requireAmount(problems, "grand_total", invoice.grandTotal());
        if (invoice.lines() == null || invoice.lines().isEmpty()) {
            problems.add("line_items must list at least one line");
        } else {
            for (int i = 0; i < invoice.lines().size(); i++) {
                ExtractedInvoice.Line line = invoice.lines().get(i);
                if (blank(line.description())) {
                    problems.add("line " + (i + 1) + " has no description");
                }
                if (line.amount() == null) {
                    problems.add("line " + (i + 1) + " has no amount");
                }
            }
        }
        if (!problems.isEmpty()) {
            return Optional.of(new CheckResult(INVOICE_FIELDS, false, String.join("; ", problems.stream()
                    .distinct().toList())));
        }
        return Optional.of(new CheckResult(INVOICE_FIELDS, true, "every required field is present and well formed"
                + (blank(invoice.poNumber()) ? "; no PO number on this invoice" : "")));
    }

    /** One arithmetic rule: a mismatch description when it disagrees, empty when it agrees, null when it cannot apply. */
    private interface Rule {
        Optional<String> mismatch(ExtractedInvoice invoice, BigDecimal tolerance);
    }

    static Optional<CheckResult> lineArithmetic(Contract contract, ExtractedInvoice invoice,
                                                List<SelfReportedAnomaly> anomalies) {
        return arithmetic(INVOICE_LINE_ARITHMETIC, contract, invoice, anomalies, "every line's quantity x unit price "
                + "is its amount", InvoiceChecks::lineMismatch);
    }

    static Optional<CheckResult> linesSum(Contract contract, ExtractedInvoice invoice, List<SelfReportedAnomaly> anomalies) {
        return arithmetic(INVOICE_LINES_SUM, contract, invoice, anomalies, "the lines add up to the subtotal",
                InvoiceChecks::sumMismatch);
    }

    static Optional<CheckResult> taxArithmetic(Contract contract, ExtractedInvoice invoice,
                                               List<SelfReportedAnomaly> anomalies) {
        if (invoice == null || taxBasis(invoice) == null) {
            return Optional.empty(); // neither a tax per line nor a rate: nothing to check the tax against
        }
        return arithmetic(INVOICE_TAX_ARITHMETIC, contract, invoice, anomalies, "the tax total is "
                + taxBasis(invoice), InvoiceChecks::taxMismatch);
    }

    static Optional<CheckResult> total(Contract contract, ExtractedInvoice invoice, List<SelfReportedAnomaly> anomalies) {
        return arithmetic(INVOICE_TOTAL, contract, invoice, anomalies, "subtotal + tax total is the grand total",
                InvoiceChecks::totalMismatch);
    }

    private static Optional<CheckResult> arithmetic(String rule, Contract contract, ExtractedInvoice invoice,
                                                    List<SelfReportedAnomaly> anomalies, String agrees, Rule check) {
        if (invoice == null) {
            return Optional.empty();
        }
        BigDecimal tolerance = tolerance(contract);
        Optional<String> mismatch = check.mismatch(invoice, tolerance);
        if (mismatch.isEmpty()) {
            return Optional.of(new CheckResult(rule, true, agrees + " (within " + tolerance.toPlainString() + ")"));
        }
        String detail = mismatch.get();
        Optional<SelfReportedAnomaly> reported = anomalies.stream().filter(a -> rule.equals(a.check())).findFirst();
        if (reported.isPresent()) {
            return Optional.of(CheckResult.review(rule, detail + ". The Agent reported: "
                    + reported.get().description()));
        }
        if (businessValid(contract)) {
            return Optional.of(new CheckResult(rule, false, detail + ". The Contract asks for a business-valid "
                    + "result, so a figure that doesn't add up must be corrected or reported as an anomaly"));
        }
        return Optional.of(CheckResult.review(rule, detail + ". The Agent did not report it, so you check it "
                + "against the document: a faithful extraction copies the invoice's own mistakes"));
    }

    private static Optional<String> lineMismatch(ExtractedInvoice invoice, BigDecimal tolerance) {
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < invoice.lines().size(); i++) {
            ExtractedInvoice.Line line = invoice.lines().get(i);
            if (line.quantity() == null || line.unitPrice() == null) {
                continue; // a line given by its amount alone has nothing to multiply
            }
            BigDecimal expected = money(line.quantity().multiply(line.unitPrice()));
            if (!agrees(expected, line.amount(), tolerance)) {
                wrong.add("line " + (i + 1) + ": " + line.quantity().stripTrailingZeros().toPlainString() + " x " + plain(line.unitPrice()) + " = "
                        + plain(expected) + ", but its amount is " + plain(line.amount()));
            }
        }
        return wrong.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", wrong));
    }

    private static Optional<String> sumMismatch(ExtractedInvoice invoice, BigDecimal tolerance) {
        BigDecimal sum = invoice.lines().stream().map(ExtractedInvoice.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return agrees(sum, invoice.subtotal(), tolerance) ? Optional.empty()
                : Optional.of("the lines add up to " + plain(money(sum)) + ", but the subtotal is "
                + plain(invoice.subtotal()));
    }

    private static Optional<String> taxMismatch(ExtractedInvoice invoice, BigDecimal tolerance) {
        BigDecimal expected = expectedTax(invoice);
        return agrees(expected, invoice.taxTotal(), tolerance) ? Optional.empty()
                : Optional.of(taxBasis(invoice) + " comes to " + plain(expected) + ", but the tax total is "
                + plain(invoice.taxTotal()));
    }

    private static Optional<String> totalMismatch(ExtractedInvoice invoice, BigDecimal tolerance) {
        BigDecimal expected = invoice.subtotal().add(invoice.taxTotal());
        return agrees(expected, invoice.grandTotal(), tolerance) ? Optional.empty()
                : Optional.of("subtotal " + plain(invoice.subtotal()) + " + tax " + plain(invoice.taxTotal()) + " = "
                + plain(money(expected)) + ", but the grand total is " + plain(invoice.grandTotal()));
    }

    /** What the tax total is checked against: every line's own tax, else the subtotal at the rate; null if neither. */
    private static String taxBasis(ExtractedInvoice invoice) {
        if (invoice.lines().stream().allMatch(l -> l.taxAmount() != null)) {
            return "the lines' tax";
        }
        if (invoice.taxRate() != null) {
            return "the subtotal at " + invoice.taxRate().stripTrailingZeros().toPlainString() + " %";
        }
        return null;
    }

    private static BigDecimal expectedTax(ExtractedInvoice invoice) {
        if (invoice.lines().stream().allMatch(l -> l.taxAmount() != null)) {
            return money(invoice.lines().stream().map(ExtractedInvoice.Line::taxAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return money(invoice.subtotal().multiply(invoice.taxRate()).divide(BigDecimal.valueOf(100)));
    }

    // ---- Findings (D7, D45): reported to the buyer, never counted ----

    static Optional<Finding> duplicateDocument(FindingFacts facts, UUID taskId) {
        if (facts.earlierItemsWithSameDocument().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(DUPLICATE_DOCUMENT, "You sent this very file before, as "
                + refs(facts.earlierItemsWithSameDocument(), taskId) + ". Check you are not paying this invoice "
                + "twice; this does not change the Agent's pay."));
    }

    static Optional<Finding> duplicateInvoice(ExtractedInvoice invoice, FindingFacts facts, UUID taskId) {
        if (invoice == null || facts.earlierItemsWithSameInvoice().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(DUPLICATE_INVOICE, "Invoice " + invoice.invoiceNumber() + " from supplier tax id "
                + invoice.supplierTaxId() + " was already extracted, as " + refs(facts.earlierItemsWithSameInvoice(),
                taskId) + ". It may be a duplicate the supplier sent twice; this does not change the Agent's pay."));
    }

    static Optional<Finding> arithmeticFinding(Contract contract, ExtractedInvoice invoice) {
        if (invoice == null) {
            return Optional.empty();
        }
        BigDecimal tolerance = tolerance(contract);
        List<String> mismatches = new ArrayList<>();
        lineMismatch(invoice, tolerance).ifPresent(mismatches::add);
        sumMismatch(invoice, tolerance).ifPresent(mismatches::add);
        if (taxBasis(invoice) != null) {
            taxMismatch(invoice, tolerance).ifPresent(mismatches::add);
        }
        totalMismatch(invoice, tolerance).ifPresent(mismatches::add);
        if (mismatches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Finding(INVOICE_ARITHMETIC, "The invoice's figures don't add up: "
                + String.join("; ", mismatches) + ". If the document says so too, the supplier's invoice is wrong; "
                + "this does not change the Agent's pay."));
    }

    // ---- helpers ----

    /** True when the arithmetic checks can run: the fields they read are all there. */
    static boolean arithmeticReady(ExtractedInvoice invoice) {
        return invoice != null && invoice.subtotal() != null && invoice.taxTotal() != null
                && invoice.grandTotal() != null && invoice.lines() != null && !invoice.lines().isEmpty()
                && invoice.lines().stream().allMatch(l -> l.amount() != null);
    }

    private static String refs(List<FindingFacts.ItemRef> refs, UUID taskId) {
        return refs.stream().map(r -> r.taskId().equals(taskId) ? "Item " + r.itemNo() + " of this task"
                : "Item " + r.itemNo() + " of task " + r.taskId()).collect(Collectors.joining(", "));
    }

    private static boolean businessValid(Contract contract) {
        return contract.params().get(EXPECTATION) instanceof ParameterValue.Text t && BUSINESS_VALID.equals(t.value());
    }

    private static BigDecimal tolerance(Contract contract) {
        return contract.params().get(AMOUNT_TOLERANCE) instanceof ParameterValue.Number n ? n.value()
                : DEFAULT_AMOUNT_TOLERANCE;
    }

    private static boolean agrees(BigDecimal expected, BigDecimal actual, BigDecimal tolerance) {
        return expected.subtract(actual).abs().compareTo(tolerance) <= 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        // Amounts read as money (120 -> 120.00); a finer figure (a unit price of 33.333) keeps its digits.
        // The reader bounds every figure first, so rescaling here is cheap.
        return value.scale() <= MONEY_SCALE ? value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY).toPlainString()
                : value.toPlainString();
    }

    private static boolean isIsoDate(String value) {
        try {
            LocalDate.parse(value);
            return value.length() == 10;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void requireText(List<String> problems, String name, String value) {
        if (blank(value)) {
            problems.add(name + " is missing");
        }
    }

    private static void requireAmount(List<String> problems, String name, BigDecimal value) {
        if (value == null) {
            problems.add(name + " is missing");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
