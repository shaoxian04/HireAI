# Signed receipts (format `hireai.receipt.v1`)

A receipt is HireAI's signed statement of how one settled Item ended: the Contract it was judged
against, fingerprints of what was asked for and what was delivered, every check, the judgements and
any ruling, and the ledger entries that moved the money (spec S11, D40). It is evidence for people
outside HireAI (auditors, insurers, Builders, a later dispute). **A receipt gates nothing:** issuing
one never blocks or changes a settlement, and nothing in HireAI reads one back to decide anything.

## When one exists

One receipt per settled subject:

- a single-deliverable Task, once its `settlements` row exists (accepted, refunded, split, or refunded
  after a failure, timeout or cancellation);
- each Item of a [multi-Item Task](data-model.md#items), once that Item's `task_item_settlements` row
  exists. (A multi-Item Task refunded as a whole gets one whole-Task receipt with `itemCount`.)
- an Item of a [sampled](sampling.md) Task, only once its inspection has ended (lot accepted, pro rata,
  waived) or the Task closed: an Item receipt states the sample (seeds and membership), and issued while a
  second sample or the resample could still be drawn it would let the Agent predict or redraw it. Items
  settled earlier (passed or refunded mid-inspection) get theirs then, with the final `sample` member.

Receipts are issued after the settlement commits, never inside it: `ReceiptIssueSweeper`
(`hireai.receipts.issue-interval`) catches up, and reading a task's receipts issues any that are
missing. Each is stored once, append-only (`receipts`, DB triggers refuse UPDATE and DELETE), with the
exact signed text.

## Reading them

| Endpoint | Who | Returns |
|---|---|---|
| `GET /api/tasks/{taskId}/receipts` | the task's Client (JWT or own API key); the assigned Agent's Builder, signed in with the BUILDER role | `WebResult` list: `id, taskId, itemNo, keyId, issuedAt, receipt` (parsed body), `signature` |
| `GET /api/receipts/{receiptId}` | same | the signed document as a JSON attachment, byte for byte |
| `GET /api/receipts/{receiptId}/credential` | same | the receipt as a W3C Verifiable Credential 2.0 (`application/vc`), see [Exports](#exports) |
| `GET /api/receipts/{receiptId}/otel` | same | the receipt as OTel `gen_ai.evaluation.result` events (OTLP/JSON), see [Exports](#exports) |
| `GET /api/receipt-keys` | anyone | JWK Set of the verification keys |

An API key is a Client-channel credential: it reads only receipts (and their exports) of tasks its
owner submitted, never as a Builder. Anyone else gets 404, as with every ownership check; an anonymous caller gets 401. The
web task page lists the receipts once something has settled, with a download per receipt.

## The document

```json
{"receipt":{…signed body…},"signature":{"alg":"Ed25519","keyId":"ed25519-…","value":"<base64url>"}}
```

JSON Schema: [`docs/formats/receipt.v1.schema.json`](../formats/receipt.v1.schema.json).
`value` is the Ed25519 (RFC 8032) signature, base64url without padding, over the UTF-8 bytes of the
**RFC 8785 (JCS)** canonical form of `receipt`. The whole document is itself canonical JSON.

### Body members

Absent facts are left out, never written as `null`. Money is a decimal string with two places
(`"8.50"`), in platform credits. Times are ISO-8601 UTC with millisecond precision. Integers are JSON
numbers.

| Member | Content |
|---|---|
| `format` | `"hireai.receipt.v1"` |
| `receiptId`, `issuedAt`, `keyId` | this receipt; `keyId` names the signing key (also in `signature`) |
| `task` | `id`, `submittedAt` |
| `item` | `no`, `count`; only on an Item's receipt (`itemCount` instead on a whole-Task receipt of a multi-Item Task) |
| `parties` | `clientRef`, `builderRef`, `agentVersionRef`: pseudonymous references, see below |
| `contract` | `template` (`key@version`), `templateKey`, `templateVersion`, `sha256` of the RFC 8785 form of the Task's frozen [Contract](#the-contract-a-receipt-names) (its output-spec equivalent for a Task from before Contracts) |
| `input` | `sha256` of the UTF-8 task description, or of the Item's input |
| `delivery` | `agentStatus`, `receivedAt`, `payloadSha256` of the result payload, and `file` (`sha256`, `mediaType`, `sizeBytes`, `fetchedAt`) when a [DeliverySnapshot](data-model.md#delivery-snapshots) was frozen; absent when nothing was delivered |
| `checks` | `attempt`, `verdict` (`PASS`/`FAIL`), `results[]` of `rule`, `passed`, `detail` |
| `sample` | on an Item of a sampled Task (spec S8): `table` (`ISO2859-1`), `aql`, `inspectionLevel`, `outcome` (`SAMPLING`/`REWORKING`/`ACCEPTED`/`PRO_RATA`/`WAIVED`), `errorRate` (pro rata only), `sampled` (whether this Item was in any sample), and `draws[]` (`round`, `stage`, `seed` as a decimal string, `lotSize`, `codeLetter`, `size`, `member`, `defects`, `decision`). The draw is reproducible from the seed: see [sampling.md](sampling.md#the-draw) |
| `judgements` | on an Item, first the buyer's reviewer (`by: REVIEWER`, `decision` `PASSED`/`REJECTED`, `round`, `sampled`, `reasonCategory`), the Agent's responses and the buyer's decisions on it; otherwise, in order: Client rejections (`decision: REJECTED`, `round`, `reasonCategory`), Agent responses (`response`: `REVISE`/`CONCEDE`/`STAND_BY`, `silent`), Client decisions (`ACCEPT`/`ARBITRATE`, `silent`), and a final Client `ACCEPTED` (`automatic: true` when the review window closed) |
| `ruling` | `disputeId`, effective `category`, `appealedBy`, and `rulings[]` (`tier`, `category`, `decidedBy`, `decidedAt`, `rationale`) |
| `fees` | `charges[]` (`type`, `clientCharged`, `builderCharged`, `platformCovered`) and their `ledgerEntries[]` |
| `settlement` | `outcome` (`ACCEPT`/`REJECT`/`SPLIT`), `price`, `paid` (to the Builder), `commission`, `refunded`, `failureReason`, `settledAt`, `ledgerEntries[]` (`id`, `party` CLIENT/BUILDER, `type`, `amount`, `at`); `paid + commission + refunded = price` |

### Parties are pseudonymous

A receipt leaves HireAI, so it carries no internal user or version id. Each party is
`hex(HMAC-SHA256(partyKey, taskId + "|" + ROLE + "|" + id))` with `ROLE` one of `CLIENT`, `BUILDER`,
`AGENT_VERSION`. Refs differ per task, so two receipts cannot be linked to one user by their refs. A
party proves its ref by asking HireAI, which recomputes it from the task and the party's account; no
one else can, without the key.

### Extending the format

New facts arrive as new optional members under `v1` (the `sample` member was added this way); the
SourceSnapshot hashes of the input documents (#104) will be too. Receipts
already issued keep their stored bytes and signature, so they keep verifying. A verifier must ignore
members it does not know. Only a change to an existing member's meaning bumps `format`.

## Verifying a receipt

1. Fetch `GET /api/receipt-keys` and pick the key whose `kid` equals `signature.keyId`. Its `x` is the
   raw 32-byte Ed25519 public key, base64url (RFC 8037).
2. Parse the document, take the `receipt` member and serialise it with RFC 8785.
3. Verify `signature.value` (base64url) over those UTF-8 bytes with the key.
4. To check a document against it, hash the document with SHA-256 and compare with `input.sha256` or
   `delivery.file.sha256`; this still works after the content was deleted.

`ReceiptIntegrationTest#verify` does exactly this with the JDK alone (`JsonCanonicalizer`, `Ed25519`
in `hireai-utility`).

## Keys and rotation

- `hireai.receipts.signing-key` (`RECEIPT_SIGNING_KEY`): the Ed25519 private key, base64 PKCS#8 DER,
  from the environment only; **never commit one**. Generate with
  `openssl genpkey -algorithm ed25519 -outform DER | base64 -w0`. The key id is derived from the public
  key (`ed25519-` + 16 hex of its SHA-256) unless `hireai.receipts.key-id` overrides it.
- Unset, the app generates a key per process and warns: its receipts stop verifying after a restart.
  Development and tests only (tests generate their own key at run time).
- `hireai.receipts.party-key` (`RECEIPT_PARTY_KEY`, at least 32 characters): the HMAC key for party
  refs, from the environment only. Unset, a per-process key with a warning. Rotating it changes refs
  only on receipts issued afterwards; keep the old key to resolve older refs.
- **Rotation:** generate a new key; add the old public key (`openssl pkey -inform DER -in old.der
  -pubout -outform DER | base64 -w0`) to `hireai.receipts.retired-public-keys`
  (`RECEIPT_RETIRED_PUBLIC_KEYS`, comma-separated, each optionally `keyId:`-prefixed so a non-derived
  id keeps its name); set the new private key; restart. New receipts carry the new `keyId`; the JWK
  Set publishes the active key first and every retired key with `status: retired`, so older receipts
  still verify. Never drop a retired key while its receipts matter.

## The Contract a receipt names

`contract.sha256` is the SHA-256 (hex) of the RFC 8785 form of the Task's frozen Contract:
`{"template":"<key>","version":<n>,"params":{…}}`, JSON Schema
[`docs/formats/contract.v1.schema.json`](../formats/contract.v1.schema.json). A parameter's value is a
number (`max`/`min` parameters), one string (`enum`; a JSON Schema parameter is the schema as a string),
an array of strings (`subset`) or a boolean (`flag`); what each Template's parameters mean is its
`contract_templates` definition ([data model](data-model.md#acceptance)). The Client and the Builder
both see the Contract on the task, so either can hash it and compare.

## Exports

Two read-only views of a stored receipt, built from its stored body at request time. They state
nothing the receipt does not, are not stored, and gate nothing. Both are RFC 8785 text. Schemas:
[`docs/formats/`](../formats/).

### W3C Verifiable Credential 2.0

`GET /api/receipts/{id}/credential` (schema `receipt-credential.v1.schema.json`):

```json
{"@context":["https://www.w3.org/ns/credentials/v2",{"receipt":{"@id":"…issuer-dependent#hireaiReceipt","@type":"@json"},"receiptSignature":{…,"@type":"@json"}}],
 "id":"urn:uuid:<receiptId>","type":["VerifiableCredential","HireAIReceiptCredential"],
 "issuer":"did:key:z6Mk…","validFrom":"<receipt issuedAt>",
 "credentialSubject":{"receipt":{…the signed body…},"receiptSignature":{"alg":"Ed25519","keyId":"…","value":"…"}},
 "proof":{"type":"DataIntegrityProof","cryptosuite":"eddsa-jcs-2022","created":"…","verificationMethod":"did:key:z6Mk…#z6Mk…","proofPurpose":"assertionMethod","@context":[…],"proofValue":"z…"}}
```

- `credentialSubject.receipt` and `receiptSignature` are the stored receipt, unchanged: read as
  `{"receipt":…,"signature":…}` they are the downloaded document and verify as above. The inline
  context makes both JSON literals (`@json`), so JSON-LD tools carry them opaquely.
- The proof follows W3C **vc-di-eddsa `eddsa-jcs-2022`**: the proof options (without `proofValue`,
  with the document's `@context`) and the credential (without `proof`) are each canonicalised with
  RFC 8785 and hashed with SHA-256; the active receipt key signs the 64 bytes `hash(options) ‖
  hash(credential)` with Ed25519; `proofValue` is that signature in base58btc multibase (`z…`). No RDF
  canonicalisation, so the JDK and `JsonCanonicalizer` are all it takes (no new dependency).
- The key is named as a `did:key` (Multikey: base58btc of `0xed01` + the raw key), so the proof verifies
  offline. That the key is HireAI's is checked against `GET /api/receipt-keys`: the did:key's raw key
  equals one JWK's `x`. `issuer` is the same did:key.
- `validFrom` is the receipt's `issuedAt`; `created` is when the credential was exported (to the
  second). The proof is always by the **active** key, so after a rotation an old receipt exports with
  the new key while its carried `receiptSignature` still names the retired one.

To verify: drop `proof`, take `proofValue` off it, check its `@context` equals the credential's,
hash both as above, decode the did:key in `verificationMethod`, verify. `ReceiptIntegrationTest#verifyCredential`
does exactly this with the JDK alone.

### OpenTelemetry `gen_ai.evaluation.result`

`GET /api/receipts/{id}/otel` (schema `receipt-otel.v1.schema.json`) is an OTLP/JSON logs export
request (`resourceLogs` → `scopeLogs` → `logRecords`; resource `service.name=hireai`, scope
`hireai.receipts`) that can be posted to any collector's `/v1/logs`. Each log record is an event
`eventName: "gen_ai.evaluation.result"` (GenAI semantic conventions), severity INFO, no trace context:

| `hireai.evaluation.kind` | `gen_ai.evaluation.name` | `score.label` | `score.value` | time |
|---|---|---|---|---|
| `checks` (when checks ran) | `hireai.contract_checks` | `pass`/`fail` (the verdict) | share of checks passed | `delivery.receivedAt`, else `settledAt` |
| `check`, one per check | the Check Library rule | `pass`/`fail` | 1 or 0 | same |
| `acceptance` (always, last) | `hireai.acceptance` | `accept`/`reject`/`split` (the settlement outcome) | `(price − refunded) / price` | `settlement.settledAt` |

`gen_ai.evaluation.explanation` says why (the failed rules, a check's detail, the ruling, an automatic
acceptance, the failure reason, the money). Every event also carries `gen_ai.response.id` (`<taskId>`,
or `<taskId>/items/<n>`), `hireai.receipt.id`, `hireai.receipt.format`, `hireai.receipt.sha256` (SHA-256
of the downloaded receipt document, tying the event to the signed bytes), `hireai.receipt.key_id`,
`hireai.task.id`, `hireai.item.no` (Items only), `hireai.contract.template` and
`hireai.contract.sha256`; the acceptance event adds `hireai.settlement.outcome` and, after a dispute,
`hireai.ruling.category`. The events are not signed: their evidence is the receipt they hash.
