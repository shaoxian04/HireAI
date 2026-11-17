package com.hireai.domain.biz.acceptance.template.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * {@code ap_invoice_extraction@1}, the first vertical Template (spec S8, D7, D45): the fields of one
 * supplier invoice, extracted from its document (an Engagement's SourceSnapshot) and delivered as a
 * JSON object. The platform checks the shape and the invoice's own arithmetic; it reports duplicates
 * and arithmetic mismatches as Findings, which never change what the Agent is paid. Findings that
 * need the buyer's reference data (supplier master data, PO existence) are a later ticket (D45).
 * Its definition is seeded by Flyway ({@code contract_templates}); these constants name it and its
 * Check Library entries, in the order they run.
 */
public final class InvoiceExtractionTemplate {

    public static final TemplateRef AP_INVOICE_EXTRACTION_V1 = new TemplateRef("ap_invoice_extraction", 1);

    /** What the Contract expects (comparison {@code enum}): faithful to the source (the default) or business-valid. */
    public static final String EXPECTATION = "expectation";
    public static final String FAITHFUL_TO_SOURCE = "faithful_to_source";
    public static final String BUSINESS_VALID = "business_valid";
    /** How far two amounts may differ and still agree (comparison {@code min}: an Offer names the tightest it accepts). */
    public static final String AMOUNT_TOLERANCE = "amount_tolerance";
    public static final BigDecimal DEFAULT_AMOUNT_TOLERANCE = new BigDecimal("0.01");

    /** The result is a JSON object. */
    public static final String INVOICE_JSON = "INVOICE_JSON";
    /** Every required field is there and well formed. */
    public static final String INVOICE_FIELDS = "INVOICE_FIELDS";
    /** Each line's quantity x unit price is its amount. */
    public static final String INVOICE_LINE_ARITHMETIC = "INVOICE_LINE_ARITHMETIC";
    /** The line amounts add up to the subtotal. */
    public static final String INVOICE_LINES_SUM = "INVOICE_LINES_SUM";
    /** The tax total is the lines' tax, or the subtotal at the tax rate. */
    public static final String INVOICE_TAX_ARITHMETIC = "INVOICE_TAX_ARITHMETIC";
    /** Subtotal + tax total is the grand total. */
    public static final String INVOICE_TOTAL = "INVOICE_TOTAL";

    /** Findings: the same file earlier, the same supplier tax id and invoice number earlier, figures that don't add up. */
    public static final String DUPLICATE_DOCUMENT = "DUPLICATE_DOCUMENT";
    public static final String DUPLICATE_INVOICE = "DUPLICATE_INVOICE";
    public static final String INVOICE_ARITHMETIC = "INVOICE_ARITHMETIC";

    /** The arithmetic checks, in the order they run. */
    public static final List<String> ARITHMETIC = List.of(INVOICE_LINE_ARITHMETIC, INVOICE_LINES_SUM,
            INVOICE_TAX_ARITHMETIC, INVOICE_TOTAL);

    /**
     * The checks a self-reported anomaly may send to review instead of rejecting (D7): the arithmetic
     * ones, where a faithful extraction of a wrong invoice disagrees with itself. A malformed result
     * (not JSON, a field missing) is always reworked.
     */
    public static final Set<String> ANOMALY_ELIGIBLE = Set.copyOf(ARITHMETIC);

    /** The Template's Check Library entries, as its seeded definition lists them. */
    public static final List<String> CHECKS = List.of(INVOICE_JSON, INVOICE_FIELDS, INVOICE_LINE_ARITHMETIC,
            INVOICE_LINES_SUM, INVOICE_TAX_ARITHMETIC, INVOICE_TOTAL, DUPLICATE_DOCUMENT, DUPLICATE_INVOICE,
            INVOICE_ARITHMETIC);

    private InvoiceExtractionTemplate() {
    }
}
