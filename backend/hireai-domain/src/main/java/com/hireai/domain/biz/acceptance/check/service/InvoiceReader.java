package com.hireai.domain.biz.acceptance.check.service;

import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;

/**
 * Port: reads an {@code ap_invoice_extraction} result's JSON into an {@link InvoiceReading} for the
 * invoice checks, without judging it. Implemented in infrastructure (the domain has no JSON library).
 */
public interface InvoiceReader {

    InvoiceReading read(String payload);
}
