---
status: accepted
---

# A three-layer Template → Offer → Contract replaces the output spec as the binding contract

**Status:** decided, not built (#46; spec: `docs/superpowers/specs/2026-11-15-acceptance-contract-spec.md`).

The task's frozen `output_spec` (format, schema, free-text `acceptanceCriteria`) was the binding
contract of Hard Invariant #4. It had two flaws.

- **On routed tasks the client wrote it and the platform picked an Agent that never agreed to it.** A
  failure still recorded `SPEC_VIOLATION` against the Builder (#46 problem 1, #50).
- **It could only check shape.** TEXT passed if non-blank, FILE passed if its URL was HTTPS,
  `acceptanceCriteria` was never checked, and an unparseable schema was silently skipped.

We replace it with three layers.

1. A platform-authored, versioned **Template** for each kind of work. It fixes which Check Library
   entries may be used, each parameter's bounds, and one comparison per parameter (`max`, `min`,
   `subset`, `enum`, `flag`).
2. A Builder-declared **Offer**: the Template versions the Agent works under, the parameter range it
   will be held to, and its price.
3. A per-Task **Contract**: one Template version plus the client's parameters, frozen at submit.

An Agent is eligible only if its Offer **covers** the Contract. With one comparison per parameter,
coverage is decidable by construction. No Agent is ever held to terms it did not accept, so #46
problems 1 and 6 disappear structurally and #50's stopgap is unnecessary.

## Considered options

- **Score or filter by JSON-Schema compatibility** (#46 option A; D1 of the 2026-08-11 notes).
  Schema subsumption is undecidable in general, and a pragmatic approximation leaves Builders exposed
  to the cases it misses.
- **Let the dispatched Agent decline before executing** (#46 option B). It already exists as a busy
  decline and stays as **ContractDecline**, but on its own it burns failover hops and leaves the
  matcher blind.
- **Clients pick a published agent schema** (#46 option C). This is closest to the choice, but it is
  per-Agent rather than per-kind-of-work, so Agents can't be compared and routing has nothing shared to
  match on.
- **Stop charging routed violations against the Builder** (#46 option D, #50). It removes the penalty
  but not the cause, and it starves the matcher of the signal.

## Consequences

- Hard Invariant #4 is reworded: the Task's frozen **Contract** is the binding contract. The
  description is intent evidence only.
- A client-authored JSON Schema survives only as a parameter of the `custom_json` Template. That
  Template matches only Agents whose Offer accepts an arbitrary schema.
- Existing agent versions migrate:
  - TEXT goes to the `text_basic` Template.
  - FILE goes to `file_basic`.
  - JSON with the Agent's own schema goes to `custom_json`, with an Offer accepting only that schema.
- In-flight tasks finish under their frozen `output_spec`.
