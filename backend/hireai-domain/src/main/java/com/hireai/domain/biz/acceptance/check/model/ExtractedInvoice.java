package com.hireai.domain.biz.acceptance.check.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An invoice as an Agent delivered it under {@code ap_invoice_extraction} (spec S8), read from its
 * JSON by the {@code InvoiceReader} port without judging it: a field that is missing, or of the wrong
 * JSON type, is null here, and {@code typeProblems} says which fields had the wrong type. The checks
 * decide what that means.
 */
public record ExtractedInvoice(String supplierName, String supplierTaxId, String invoiceNumber, String invoiceDate,
                               String currency, String poNumber, BigDecimal subtotal, BigDecimal taxTotal,
                               BigDecimal grandTotal, BigDecimal taxRate, List<Line> lines,
                               List<String> typeProblems) {

    /** One line of the invoice; {@code quantity}, {@code unitPrice} and {@code taxAmount} are optional. */
    public record Line(String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                       BigDecimal taxAmount) {
    }

    public ExtractedInvoice {
        lines = lines == null ? null : List.copyOf(lines);
        typeProblems = typeProblems == null ? List.of() : List.copyOf(typeProblems);
    }

    /**
     * The duplicate key (D45): the supplier's tax id and the invoice number, upper-cased with spaces,
     * dashes and dots dropped, so "GB 123-456" and "gb123456" are one supplier. Empty when either is missing.
     */
    public Optional<String> duplicateKey() {
        String tax = normalise(supplierTaxId);
        String number = normalise(invoiceNumber);
        return tax.isEmpty() || number.isEmpty() ? Optional.empty() : Optional.of(tax + "|" + number);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-/]", "");
    }
}
