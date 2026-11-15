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

Receipts are issued after the settlement commits, never inside it: `ReceiptIssueSweeper`
(`hireai.receipts.issue-interval`) catches up, and reading a task's receipts issues any that are
missing. Each is stored once, append-only (`receipts`, DB triggers refuse UPDATE and DELETE), with the
exact signed text.

## Reading them

| Endpoint | Who | Returns |
|---|---|---|
| `GET /api/tasks/{taskId}/receipts` | the task's Client (JWT or own API key); the assigned Agent's Builder, signed in with the BUILDER role | `WebResult` list: `id, taskId, itemNo, keyId, issuedAt, receipt` (parsed body), `signature` |
| `GET /api/receipts/{receiptId}` | same | the signed document as a JSON attachment, byte for byte |
| `GET /api/receipt-keys` | anyone | JWK Set of the verification keys |

An API key is a Client-channel credential: it reads only receipts of tasks its owner submitted, never
as a Builder. Anyone else gets 404, as with every ownership check; an anonymous caller gets 401. The
web task page lists the receipts once something has settled, with a download per receipt.

## The document

```json
{"receipt":{…signed body…},"signature":{"alg":"Ed25519","keyId":"ed25519-…","value":"<base64url>"}}
```

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
| `contract` | `template` (`key@version`), `templateKey`, `templateVersion`, `sha256` of the RFC 8785 form of the Task's frozen Contract (its output-spec equivalent for a Task from before Contracts) |
| `input` | `sha256` of the UTF-8 task description, or of the Item's input |
| `delivery` | `agentStatus`, `receivedAt`, `payloadSha256` of the result payload, and `file` (`sha256`, `mediaType`, `sizeBytes`, `fetchedAt`) when a [DeliverySnapshot](data-model.md#delivery-snapshots) was frozen; absent when nothing was delivered |
| `checks` | `attempt`, `verdict` (`PASS`/`FAIL`), `results[]` of `rule`, `passed`, `detail` |
| `judgements` | in order: Client rejections (`decision: REJECTED`, `round`, `reasonCategory`), Agent responses (`response`: `REVISE`/`CONCEDE`/`STAND_BY`, `silent`), Client decisions (`ACCEPT`/`ARBITRATE`, `silent`), and a final Client `ACCEPTED` (`automatic: true` when the review window closed) |
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

New facts arrive as new optional members under `v1`: the sample membership and seed of sampled Items
(#105) and the SourceSnapshot hashes of the input documents (#104) will be added this way. Receipts
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

Not built yet: W3C VC 2.0 and OTel `gen_ai.evaluation.result` exports (D40's later step).
