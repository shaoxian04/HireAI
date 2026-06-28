# CI Pipeline Design — Build + Test Quality Gate

**Date:** 2026-06-29
**Status:** Approved (brainstorming) → pending implementation plan
**Branch:** `worktree-ci+github-actions` (worktree off `origin/main`)

## Goal

Add a **build + test quality gate** for the HireAI monorepo using **GitHub Actions**.
No deployment, no Docker image build, no coverage gate — just: on every PR to `main`
and every push to `main`, build and test the backend and frontend, and block merges on red.

This is a Final Year Project; the gate exists to (a) catch regressions before they reach
`main` and (b) demonstrate professional engineering practice.

## Scope

### In scope
- GitHub Actions workflows that build + test `backend/` (Spring Boot, Maven) and
  `frontend/` (Next.js, npm).
- The **full** backend suite, including the 59 Testcontainers integration tests
  (real Postgres + RabbitMQ booted on the runner via its preinstalled Docker).
- Frontend: ESLint, `next build` (which typechecks), and the Vitest suite.

### Out of scope (YAGNI for this gate)
- **`arbitration/`** — Python FastAPI service, not started, no tests.
- **`demo-agent/`** — Python demo stub; not part of the product. (A smoke-test job can be
  added later if desired.)
- **Deployment / CD** to Railway — explicitly deferred.
- **Docker image build** — explicitly deferred.
- **Coverage gate** — no Jacoco is wired up in `backend/pom.xml` today; can be added later.

## Architecture

Two workflow files in `.github/workflows/`, **split by component**, each with a `paths`
filter so a change only triggers its own pipeline. This matters because the repo receives
frequent **docs-only** commits, which should skip the heavy Java/Testcontainers build
entirely; likewise a frontend-only change should not boot Postgres.

### 1. `.github/workflows/backend-ci.yml` — "Backend CI"

- **Triggers:**
  - `pull_request` → `branches: [main]`
  - `push` → `branches: [main]`
  - `workflow_dispatch` (manual re-run button for the heavy suite)
  - Path filter: `paths: ['backend/**', '.github/workflows/backend-ci.yml']`
- **Permissions:** `contents: read`
- **Concurrency:** group per ref, `cancel-in-progress: true` (supersede stale runs).
- **Job `build-test`** on `ubuntu-latest`:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` — Temurin **21**, `cache: maven`
  3. `mvn -f backend/pom.xml -B -ntp package`
     - Builds all 7 COLA modules in layer order and runs the full 322-test suite.
     - Testcontainers boots Postgres + RabbitMQ using the runner's **preinstalled Docker**;
       no `services:` block is needed (Testcontainers owns container lifecycle).
  4. **On failure:** upload Surefire reports
     (`backend/**/target/surefire-reports/**`) as an artifact, so failures can be read
     without re-running.

### 2. `.github/workflows/frontend-ci.yml` — "Frontend CI"

- **Triggers:**
  - `pull_request` → `branches: [main]`
  - `push` → `branches: [main]`
  - Path filter: `paths: ['frontend/**', '.github/workflows/frontend-ci.yml']`
- **Permissions:** `contents: read`
- **Concurrency:** group per ref, `cancel-in-progress: true`.
- **Job `build-test`** on `ubuntu-latest`, `defaults.run.working-directory: frontend`:
  1. `actions/checkout@v4`
  2. `actions/setup-node@v4` — Node **22**, `cache: npm`,
     `cache-dependency-path: frontend/package-lock.json`
  3. `npm ci`
  4. `npm run lint` (ESLint)
  5. `npm run build` (`next build` — also typechecks)
  6. `npx vitest run` (the 59 frontend tests)

## Key decisions

- **Node 22** — Next 16 + React 19 require Node ≥ 20.9; 22 is the current LTS.
- **Two workflow files over one** — cleaner independent triggering via `paths` filters,
  so docs-only and single-component changes don't run the other pipeline.
- **No `services:` block for the backend** — Testcontainers manages Postgres + RabbitMQ
  itself; the runner only needs Docker, which `ubuntu-latest` provides.
- **`workflow_dispatch` on backend only** — lets the heavy suite be re-triggered manually.

## Error handling / failure modes

- **A test fails** → the `mvn`/`vitest` step exits non-zero → the job fails → the PR check
  is red → merge blocked (once branch protection is on; see below).
- **Backend failure diagnosis** → Surefire reports are uploaded as an artifact on failure.
- **Path-filter + required-check caveat** → if a required status check is configured for a
  job that gets path-filtered out, the PR check can sit "pending". For a solo FYP without
  branch protection this is a non-issue today; documented here so it is a conscious choice.
  If branch protection is later enabled, use a lightweight always-runs "gate" job or
  `paths-ignore` semantics to avoid stuck checks.

## Verification strategy

Real CI runs are the verification — local `act` is not required.

1. Statically lint both workflow files with **`actionlint`** before pushing.
2. Push branch `worktree-ci+github-actions`, open a **PR into `main`**. Because
   `pull_request` workflows run from the PR's own head, **both pipelines execute on that PR
   itself** — a real green/red signal.
3. Watch via `gh pr checks` / `gh run watch`; fix anything red; report.
4. **Do not merge** without the user's explicit go-ahead.

## Follow-ups (not in this change)

- Optional: add a Maven wrapper (`mvnw`) for build reproducibility.
- Optional: Jacoco coverage gate (the user's standards target 80%).
- Optional: `demo-agent/` Python smoke-test job.
- Later phases: Docker image build, then CD to Railway.
