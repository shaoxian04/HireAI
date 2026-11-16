# Open formats

JSON Schemas (draft 2020-12) of the documents HireAI hands to people outside it. The prose, and how
to verify each one, is in [`docs/details/receipts.md`](../details/receipts.md).

| Schema | Document |
|---|---|
| [`receipt.v1.schema.json`](receipt.v1.schema.json) | a signed receipt, `GET /api/receipts/{id}` |
| [`receipt-credential.v1.schema.json`](receipt-credential.v1.schema.json) | the receipt as a W3C Verifiable Credential 2.0, `GET /api/receipts/{id}/credential` |
| [`receipt-otel.v1.schema.json`](receipt-otel.v1.schema.json) | the receipt as OTel `gen_ai.evaluation.result` events, `GET /api/receipts/{id}/otel` |
| [`contract.v1.schema.json`](contract.v1.schema.json) | a Task's frozen Contract, whose RFC 8785 form a receipt hashes |

`ReceiptIntegrationTest` validates live documents against these files, so they cannot drift from
what the API serves.
