package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.acceptance.check.model.ExtractedInvoice;
import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonInvoiceReaderTest {

    private final JacksonInvoiceReader reader = new JacksonInvoiceReader();

    @Test
    void readsEveryFieldWithExactAmounts() {
        InvoiceReading reading = reader.read("""
                {"supplier_name":"Acme","supplier_tax_id":"GB1","invoice_number":"INV-1","invoice_date":"2026-11-02",
                 "currency":"GBP","po_number":null,"subtotal":0.10,"tax_total":0.02,"grand_total":0.12,"tax_rate":20,
                 "line_items":[{"description":"Pens","quantity":1,"unit_price":0.1,"amount":0.10}]}""");

        ExtractedInvoice invoice = ((InvoiceReading.Read) reading).invoice();
        assertThat(invoice.supplierName()).isEqualTo("Acme");
        assertThat(invoice.poNumber()).isNull();
        assertThat(invoice.subtotal()).isEqualByComparingTo("0.10");
        assertThat(invoice.grandTotal().toPlainString()).isEqualTo("0.12");
        assertThat(invoice.lines()).singleElement().satisfies(l -> assertThat(l.unitPrice()).isEqualByComparingTo("0.1"));
        assertThat(invoice.typeProblems()).isEmpty();
    }

    @Test
    void namesFieldsOfTheWrongTypeAndLeavesThemOut() {
        ExtractedInvoice invoice = ((InvoiceReading.Read) reader.read(
                "{\"subtotal\":\"10.00\",\"invoice_number\":42,\"line_items\":[{\"amount\":\"1\"}, 3]}")).invoice();

        assertThat(invoice.subtotal()).isNull();
        assertThat(invoice.invoiceNumber()).isNull();
        assertThat(invoice.typeProblems()).containsExactlyInAnyOrder("subtotal", "invoice_number",
                "line_items[1].amount", "line_items[2]");
    }

    @Test
    void anAmountNoInvoiceHasIsRefusedBeforeAnyArithmetic() {
        String digits = "9".repeat(10_000);
        long started = System.nanoTime();
        ExtractedInvoice huge = ((InvoiceReading.Read) reader.read(
                "{\"subtotal\":1E999999999,\"tax_total\":1E-999999999,\"grand_total\":1234567890123456789.5}")).invoice();
        InvoiceReading longNumber = reader.read("{\"subtotal\":" + digits + "}");

        assertThat(huge.subtotal()).isNull();
        assertThat(huge.taxTotal()).isNull();
        assertThat(huge.grandTotal()).isNull();
        assertThat(huge.typeProblems()).containsExactlyInAnyOrder("subtotal (out of range)", "tax_total (out of range)",
                "grand_total (out of range)");
        // Refused by the parser's number-length limit, or as out of range: either way never computed with.
        if (longNumber instanceof InvoiceReading.Read read) {
            assertThat(read.invoice().subtotal()).isNull();
        }
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(2));
    }

    @Test
    void somethingThatIsNotAJsonObjectIsUnreadable() {
        assertThat(reader.read("Extracted from item 1")).isInstanceOf(InvoiceReading.Unreadable.class);
        assertThat(reader.read("[1,2]")).isInstanceOf(InvoiceReading.Unreadable.class);
        assertThat(reader.read("")).isInstanceOf(InvoiceReading.Unreadable.class);
    }
}
