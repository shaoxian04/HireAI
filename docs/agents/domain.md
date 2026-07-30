# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the glossary / ubiquitous language. May not exist yet.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in.
- **`docs/details/architecture-decisions.md`** — the pre-existing aggregated rationale for the backend's structural choices (COLA modules, rich aggregates, thin app layer, exceptions in `utility`, `DO` naming, OAuth no-silent-link), including where HireAI deliberately diverges from the COLA reference. It predates `docs/adr/`; treat it as a standing set of ADRs. **New** decisions go in `docs/adr/NNNN-*.md`.
- **`docs/details/`** generally — the read-on-demand detail index that `CLAUDE.md` links, each entry carrying a "Read before X" trigger (build status, architecture, DDD conventions, data model, programmatic channel, frontend, identity & authz, demo runbook). Follow the trigger that matches your task rather than preloading all of them.
- **`docs/post-mortem/`** — real mistakes from this project. `CLAUDE.md` names which ones are mandatory reading before auth, security-config, or controller-test changes.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

This is a **single-context** repo: one `CONTEXT.md` and one `docs/adr/` at the root.

```
/
├── CONTEXT.md
├── docs/
│   ├── adr/
│   │   ├── 0001-....md
│   │   └── 0002-....md
│   ├── details/          ← read-on-demand deep docs, indexed from CLAUDE.md
│   └── post-mortem/
├── backend/              ← Spring Boot (DDD, COLA multi-module)
├── arbitration/          ← Python FastAPI + LangGraph
├── frontend/             ← Next.js
└── mcp/                  ← Python MCP server facade
```

The four source trees are separate services, not separate bounded contexts with separate glossaries — they share one domain vocabulary (Task, Agent, Builder, escrow, output spec, ruling, settlement). If that stops being true, switch to multi-context by adding a root `CONTEXT-MAP.md` pointing at a per-tree `CONTEXT.md`, plus `<tree>/docs/adr/` for tree-scoped decisions.

## Source of truth

Notion is authoritative over any local doc:

- **SAD** wins on technical matters — architecture, schema, domain design.
- **PRD** wins on product scope — what's in or out of the MVP.

Links are in `CLAUDE.md`. The `docs/details/*` files distil these for fast access; when a local file disagrees with Notion, Notion wins — update the local file. Exception: `docs/details/build-status.md` is the source of truth for what is actually **built vs pending**, over any *target* design the SAD describes.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR — or a decision recorded in `docs/details/architecture-decisions.md` — surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_

The same applies to the **hard invariants** in `CLAUDE.md` (escrow before execution, append-only money & audit, deterministic money path, output spec as binding contract, server-side identity from JWT, signed HTTPS-only Agent I/O). Those are enforced in code and schema triggers — contradicting one is a stop-and-ask, not a flag-and-continue.
