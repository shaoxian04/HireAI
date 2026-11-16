import json

from app import extract_invoice, item_results


def _pdf(*lines: str) -> bytes:
    drawn = "\n".join(f"({line}) Tj" for line in lines)
    return f"%PDF-1.4\n1 0 obj << >> stream\nBT\n{drawn}\nET\nendstream\n".encode("latin-1")


INVOICE = _pdf("Supplier: Acme Supplies Ltd", "Tax ID: GB123456789", "Invoice number: INV-1001", "Date: 2026-11-02",
               "Currency: GBP", "PO: PO-77", "Line: Widgets | 2 | 50.00 | 100.00", "Line: Delivery | 1 | 20.00 | 20.00",
               "Subtotal: 120.00", "Tax rate: 20", "Tax: 24.00", "Total: 150.00")


def test_an_invoice_document_is_extracted_faithfully_with_its_mistake():
    invoice = extract_invoice(INVOICE)

    assert invoice["invoice_number"] == "INV-1001"
    assert invoice["supplier_tax_id"] == "GB123456789"
    assert invoice["line_items"][0] == {"description": "Widgets", "quantity": 2.0, "unit_price": 50.0, "amount": 100.0}
    assert invoice["grand_total"] == 150.0  # copied as printed, though 120 + 24 = 144
    assert invoice["tax_rate"] == 20.0
    assert extract_invoice(b"%PDF-1.7\ninvoice") is None


def test_the_mismatch_is_reported_unless_the_item_says_unreported():
    items = [{"itemNo": 1, "input": "a.pdf"}, {"itemNo": 2, "input": "b.pdf #unreported"}]
    results = item_results(items, {1: INVOICE, 2: INVOICE})

    assert json.loads(results[0]["resultPayloadJson"])["invoice_number"] == "INV-1001"
    assert [a["check"] for a in results[0]["anomalies"]] == ["INVOICE_TOTAL"]
    assert "anomalies" not in results[1]
