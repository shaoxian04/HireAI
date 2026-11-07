---
status: accepted
---

# Launch on public documents; treat builder verification as the ladder to confidential work

**Status:** decided, not built.

[ADR 0004](./0004-launch-vertical-document-grounded-extraction.md) chose document-grounded
extraction because the ground truth sits inside the task submission. That criterion says nothing
about *sensitivity*, and the highest-value documents in the vertical — invoices, contracts, résumés
— are confidential. This ADR records where inside the vertical to start, and why.

**Start with documents that are already public**: company filings, regulatory notices, tenders and
procurement notices, court records, planning applications, published price lists, scientific papers.
The ground-truth criterion holds exactly as well, and there is no confidentiality conversation to
have. Buyers exist: finance research, legal, procurement monitoring, competitive intelligence.

## Why not start with invoices

Because of what the architecture does with the document. HireAI **hosts no Agents itself** — an
Agent is a self-hosted webhook run by a third party, on infrastructure the platform has no
visibility into. Under this vertical, dispatch would send the client's document there (today it
sends neither a document nor the task text: issue #45). After it lands, HireAI cannot control
retention, jurisdiction, sub-processors, or whether the content becomes someone's training data.

Hard Invariant #6 does not cover this and was never meant to: HTTPS plus a short-lived signed
dispatch token protects the document **in transit**, not the recipient's handling of it.

Nor is there any vetting to fall back on. `agents.status` includes `PENDING_VERIFICATION`
(`V3__agents.sql:12-13`), but `AgentModel.activate()` is a transition the **builder triggers on
their own agent** — no identity, no data-handling agreement, no retention commitment is captured
anywhere. The status is a name with nothing behind it.

For an AP team that adds up to a vendor security review and a DPA, with HireAI as processor and
every builder an unapproved sub-processor. Résumés are worse (PII under GDPR/PDPA); contracts worse
still. Note the contrast with the rejected video vertical, where the input was something the client
intended to publish anyway — here the *input* is the sensitive asset, not the output.

## The ladder

Public documents are the wedge, not the destination. The sequence is: launch on public work → prove
the adjudication layer on real tasks → accumulate reputation history → give `PENDING_VERIFICATION`
real content (builder identity, signed data-handling and retention terms, a verified tier the
matcher can prefer) → then confidential work becomes reachable.

Redaction-before-dispatch was considered and rejected for this vertical: on an invoice the sensitive
fields *are* the payload.

## Consequences

- **A ceiling is now on the record.** The self-hosted webhook model is the single design decision
  most likely to cap this business. Every serious buyer of confidential document work eventually
  asks where their data goes, and "a stranger's server" does not win that conversation. The
  alternative — hosting Agent code in HireAI's own infrastructure, as Apify does, so documents never
  leave — would solve confidentiality and builder onboarding friction together, at the cost of
  detonating the dispatch architecture, rewriting Invariant #6, abandoning "hosts no Agents itself",
  and absorbing sandboxing and compute costs. Not now. But this is the constraint to revisit first
  when the market pushes back, and it should not be rediscovered from scratch.

- **The arbitrator cannot read the chosen evidence type.** `fetch_result_content` decodes as UTF-8
  only when the content-type contains `text` or `json`, or is absent; anything else returns the literal
  string `[binary content omitted: N bytes, content-type ...]` (`arbitration/app/graph/tools.py:65-68`).
  There is no PDF library in `arbitration/pyproject.toml` and no vision path. A PDF invoice today
  would be adjudicated on the words "binary content omitted". PDF text extraction or a vision model
  is a hard prerequisite for the vertical, not an enhancement.

- **Public-source documents make the input problem cheap.** Because the wedge documents are already
  public, a URL reference is a sufficient transport and no upload/storage surface is needed on day
  one — the SSRF-guarded fetch pattern already exists on both sides
  (`WebhookUrlValidator`, `_assert_public_https`). Confidential work later needs real storage.
