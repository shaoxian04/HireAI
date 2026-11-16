package com.hireai.domain.biz.acceptance.check.model;

/**
 * What the {@code InvoiceReader} port made of an {@code ap_invoice_extraction} result: the invoice it
 * read, or why the result is not a JSON object at all.
 */
public sealed interface InvoiceReading {

    record Read(ExtractedInvoice invoice) implements InvoiceReading {
    }

    record Unreadable(String reason) implements InvoiceReading {
    }
}
