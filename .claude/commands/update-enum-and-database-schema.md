---
name: update-enum-and-database-schema
description: Workflow command scaffold for update-enum-and-database-schema in HireAI.
allowed_tools: ["Bash", "Read", "Write", "Grep", "Glob"]
---

# /update-enum-and-database-schema

Use this workflow when working on **update-enum-and-database-schema** in `HireAI`.

## Goal

Updates an enum in the domain model and synchronizes the corresponding database schema (migration) and documentation.

## Common Files

- `backend/src/main/java/com/hireai/domain/biz/*/enums/*.java`
- `backend/src/main/resources/db/migration/*.sql`
- `docs/details/data-model.md`

## Suggested Sequence

1. Understand the current state and failure mode before editing.
2. Make the smallest coherent change that satisfies the workflow goal.
3. Run the most relevant verification for touched files.
4. Summarize what changed and what still needs review.

## Typical Commit Signals

- Update the enum definition in the domain model.
- Update the corresponding database migration (e.g., CHECK constraint).
- Update documentation to reflect the new lifecycle or allowed values.

## Notes

- Treat this as a scaffold, not a hard-coded script.
- Update the command if the workflow evolves materially.