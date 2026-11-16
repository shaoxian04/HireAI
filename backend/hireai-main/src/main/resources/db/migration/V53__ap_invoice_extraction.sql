-- #106 (spec #87 S8, D7, D45): the first vertical Template, ap_invoice_extraction@1, and what an Item
-- now keeps beside its checks.

-- 1. What was reported with an Item's result, as validation_reports keeps it for a whole-Task result
--    (V48): the Findings ([{code, detail}], never counted, D7) and the Agent's self-reported anomalies
--    ([{check, description}]). Each check in `checks` also carries its "outcome" (PASS / REJECT / REVIEW);
--    rows written before this read back from "passed".
ALTER TABLE task_items ADD COLUMN findings  JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE task_items ADD COLUMN anomalies JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 2. The duplicate key of each invoice an Item delivered (D45): the supplier's tax id and the invoice
--    number, normalised (ExtractedInvoice.duplicateKey). Read only by the DUPLICATE_INVOICE Finding,
--    scoped to one client's own Items; replaced when the Item's result is (a rework or a revision).
--    Operational, not money: no append-only trigger.
CREATE TABLE item_invoice_keys (
    item_id     UUID        PRIMARY KEY REFERENCES task_items (id),
    task_id     UUID        NOT NULL,
    item_no     INT         NOT NULL,
    client_id   UUID        NOT NULL REFERENCES users (id),
    invoice_key TEXT        NOT NULL CHECK (char_length(invoice_key) BETWEEN 3 AND 1000),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_item_invoice_keys_client_key ON item_invoice_keys (client_id, invoice_key);

-- 3. ap_invoice_extraction@1. Its checks are Check Library entries (InvoiceExtractionTemplate lists them in
--    this order); DUPLICATE_DOCUMENT, DUPLICATE_INVOICE and INVOICE_ARITHMETIC are Findings. anomalyEligible
--    names the arithmetic checks: a reported anomaly sends their failure to review, never the shape checks.
--    expectation (enum) is faithful_to_source when unset (the Template's expectationMode); amount_tolerance
--    uses "min": an Offer names the tightest tolerance it can be held to (unset = 0.01).
INSERT INTO contract_templates (template_key, version, definition) VALUES
('ap_invoice_extraction', 1, '{
  "title": "Invoice extraction",
  "description": "The fields of one supplier invoice, extracted from its document and delivered as a JSON object: supplier_name, supplier_tax_id, invoice_number, invoice_date (YYYY-MM-DD), currency (ISO 4217), po_number (or null), line_items [{description, quantity, unit_price, amount, tax_amount}], subtotal, tax_total, grand_total and, optionally, tax_rate (percent). Amounts are JSON numbers. The platform checks the fields and the invoice''s own arithmetic. A malformed result is refunded. Figures that don''t add up go to your review: by default the extraction must be faithful to the document, even where the document itself is wrong, and the Agent reports what it noticed. Duplicates and figures that don''t add up are reported to you as Findings, which never change what the Agent is paid.",
  "outputShape": "JSON",
  "checks": ["INVOICE_JSON", "INVOICE_FIELDS", "INVOICE_LINE_ARITHMETIC", "INVOICE_LINES_SUM",
             "INVOICE_TAX_ARITHMETIC", "INVOICE_TOTAL", "DUPLICATE_DOCUMENT", "DUPLICATE_INVOICE",
             "INVOICE_ARITHMETIC"],
  "anomalyEligible": ["INVOICE_LINE_ARITHMETIC", "INVOICE_LINES_SUM", "INVOICE_TAX_ARITHMETIC", "INVOICE_TOTAL"],
  "parameters": [
    {"name": "expectation", "label": "Expectation",
     "description": "faithful_to_source (the default): copy the document as it is, even its mistakes, and report what does not add up; such an invoice goes to your review. business_valid: the figures must add up, or the Agent must report why; a result that does not add up and says nothing is reworked.",
     "comparison": "enum", "bounds": {"allowedValues": ["faithful_to_source", "business_valid"]}},
    {"name": "amount_tolerance", "label": "Amount tolerance",
     "description": "How far two amounts may differ and still agree (0 to 1; unset is 0.01). An Offer names the tightest tolerance it can be held to.",
     "comparison": "min", "bounds": {"minimum": 0, "maximum": 1}}
  ],
  "expectationMode": "FAITHFUL_TO_SOURCE",
  "rulingSettlement": {"FULFILLED": 1, "PARTIALLY_FULFILLED": 0.5, "NOT_FULFILLED": 0}
}');

-- 4. UNREVIEWED: an Item a check sent to review (D7) that nobody judged before the review window closed is
--    refunded, not accepted on silence (owner decision on #87, 2026-11-16), and does not count towards
--    the Agent's reputation.
ALTER TABLE task_items DROP CONSTRAINT IF EXISTS task_items_status_check;
ALTER TABLE task_items ADD CONSTRAINT task_items_status_check CHECK (status IN (
    'AWAITING_RESULT', 'PENDING_REVIEW', 'ACCEPTED', 'SPEC_VIOLATION', 'FAILED',
    'REJECTED', 'CONCEDED', 'PRO_RATA', 'UNREVIEWED'));
