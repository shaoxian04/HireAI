---
name: add-new-domain-aggregate-vertical-slice
description: Workflow command scaffold for add-new-domain-aggregate-vertical-slice in HireAI.
allowed_tools: ["Bash", "Read", "Write", "Grep", "Glob"]
---

# /add-new-domain-aggregate-vertical-slice

Use this workflow when working on **add-new-domain-aggregate-vertical-slice** in `HireAI`.

## Goal

Implements a new domain aggregate with full vertical slice: domain model, enums, value objects, events, repository contract, persistence, app services, DTOs, controller, and integration tests.

## Common Files

- `backend/src/main/java/com/hireai/domain/biz/*/enums/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/model/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/event/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/service/*.java`
- `backend/src/main/java/com/hireai/domain/biz/*/repository/*.java`
- `backend/src/main/resources/db/migration/*.sql`

## Suggested Sequence

1. Understand the current state and failure mode before editing.
2. Make the smallest coherent change that satisfies the workflow goal.
3. Run the most relevant verification for touched files.
4. Summarize what changed and what still needs review.

## Typical Commit Signals

- Define enums and value objects in domain model (e.g., enums, OutputSpec, aggregate root).
- Add domain service(s), event(s), and repository contract.
- Create database migration for new table.
- Implement JPA persistence: entity, repository, mapper, implementation.
- Add application service interfaces and implementations.

## Notes

- Treat this as a scaffold, not a hard-coded script.
- Update the command if the workflow evolves materially.