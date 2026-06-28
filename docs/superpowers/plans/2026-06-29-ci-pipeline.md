# CI Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions build + test quality gate for the HireAI monorepo — backend (Maven, full suite incl. Testcontainers) and frontend (Next.js + Vitest) — gating PRs to and pushes to `main`.

**Architecture:** Two path-filtered workflow files under `.github/workflows/`, one per component, so a change only triggers its own pipeline. Each workflow includes *its own file* in its path filter, which is what makes both pipelines run on the very PR that introduces them. Verification is a real PR run, not local emulation.

**Tech Stack:** GitHub Actions, `ubuntu-latest`; `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 21), `actions/setup-node@v4` (Node 22), `actions/upload-artifact@v4`; Maven; npm + Vitest.

## Global Constraints

Copied verbatim from `docs/superpowers/specs/2026-06-29-ci-pipeline-design.md`. Every task implicitly includes these.

- Runners: `ubuntu-latest` (provides Docker + Maven preinstalled).
- Backend build/test command: `mvn -f backend/pom.xml -B -ntp package` — runs all 7 COLA modules + full 322-test suite. Testcontainers boots Postgres + RabbitMQ via the runner's Docker; **no `services:` block**.
- Backend Java: Temurin **21**, `cache: maven`.
- Frontend Node: **22**, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`; steps `npm ci` → `npm run lint` → `npm run build` → `npx vitest run`; `working-directory: frontend`.
- Triggers: `pull_request` → `branches: [main]` and `push` → `branches: [main]`, each `paths`-filtered to its component **plus its own workflow file**. `workflow_dispatch` on backend only.
- Two files: `.github/workflows/backend-ci.yml`, `.github/workflows/frontend-ci.yml`.
- Every workflow: `permissions: contents: read`; `concurrency` group per ref with `cancel-in-progress: true`.
- Excluded (YAGNI): `arbitration/`, `demo-agent/`, deployment/CD, Docker image build, coverage gate.
- Commit messages: conventional commits (`ci:`/`docs:`), **no AI attribution** (user's global git setting).
- **Do not merge any PR** without the user's explicit per-merge go-ahead.

## File Structure

| File | Responsibility |
|---|---|
| `.github/workflows/backend-ci.yml` | Build + full-suite test of `backend/` on Maven/JDK 21. |
| `.github/workflows/frontend-ci.yml` | Lint + build + Vitest of `frontend/` on Node 22. |

Local validation tool: `npx --yes js-yaml <file>` (Node is already available via the frontend toolchain) confirms each workflow is well-formed YAML before pushing. Authoritative schema validation is the real GitHub Actions run in Task 3.

---

### Task 1: Backend CI workflow

**Files:**
- Create: `.github/workflows/backend-ci.yml`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a workflow named `Backend CI` with a job id `build-test`. Task 3 watches this check by name.

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/backend-ci.yml`:

```yaml
name: Backend CI

on:
  pull_request:
    branches: [main]
    paths:
      - 'backend/**'
      - '.github/workflows/backend-ci.yml'
  push:
    branches: [main]
    paths:
      - 'backend/**'
      - '.github/workflows/backend-ci.yml'
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: backend-ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-test:
    name: Build & test (Maven, full suite)
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Build and test (all modules, full suite incl. Testcontainers)
        run: mvn -f backend/pom.xml -B -ntp package

      - name: Upload Surefire reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: backend-surefire-reports
          path: backend/**/target/surefire-reports/**
          if-no-files-found: ignore
          retention-days: 7
```

- [ ] **Step 2: Validate the YAML parses**

Run (from the worktree root):
```bash
npx --yes js-yaml .github/workflows/backend-ci.yml > /dev/null && echo "YAML OK"
```
Expected: prints `YAML OK` with no parse error. (If `actionlint` happens to be installed, also run `actionlint .github/workflows/backend-ci.yml` for schema/expression checks — optional.)

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/backend-ci.yml
git commit -m "ci: add backend build + full-suite test workflow"
```

---

### Task 2: Frontend CI workflow

**Files:**
- Create: `.github/workflows/frontend-ci.yml`

**Interfaces:**
- Consumes: nothing from Task 1 (independent workflow).
- Produces: a workflow named `Frontend CI` with a job id `build-test`. Task 3 watches this check by name.

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/frontend-ci.yml`:

```yaml
name: Frontend CI

on:
  pull_request:
    branches: [main]
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend-ci.yml'
  push:
    branches: [main]
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend-ci.yml'

permissions:
  contents: read

concurrency:
  group: frontend-ci-${{ github.ref }}
  cancel-in-progress: true

env:
  NEXT_TELEMETRY_DISABLED: '1'

jobs:
  build-test:
    name: Build & test (Next.js, Vitest)
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Node 22
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Lint
        run: npm run lint

      - name: Build
        run: npm run build

      - name: Test
        run: npx vitest run
```

- [ ] **Step 2: Validate the YAML parses**

Run (from the worktree root):
```bash
npx --yes js-yaml .github/workflows/frontend-ci.yml > /dev/null && echo "YAML OK"
```
Expected: prints `YAML OK` with no parse error.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/frontend-ci.yml
git commit -m "ci: add frontend lint + build + test workflow"
```

---

### Task 3: Verify on a real PR

**Files:** none (verification only).

**Interfaces:**
- Consumes: the `Backend CI` and `Frontend CI` workflows from Tasks 1–2.
- Produces: a green PR demonstrating the gate. No merge.

- [ ] **Step 1: Confirm `gh` is authenticated**

```bash
gh auth status
```
Expected: shows a logged-in account. If not, tell the user to run `! gh auth login` in the session, then continue.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin worktree-ci+github-actions
```
Expected: branch created on `origin`.

- [ ] **Step 3: Open the PR into `main`**

```bash
gh pr create --base main --head worktree-ci+github-actions \
  --title "ci: add GitHub Actions build + test quality gate" \
  --body "Adds two path-filtered workflows: Backend CI (Maven full suite incl. Testcontainers) and Frontend CI (lint + next build + vitest). Triggered on PRs/pushes to main. Design: docs/superpowers/specs/2026-06-29-ci-pipeline-design.md. This PR itself exercises both pipelines (each workflow file is in its own path filter)."
```
Expected: PR URL printed. Both `Backend CI` and `Frontend CI` checks should appear because the PR modifies each workflow file (each is in its own `paths` filter).

- [ ] **Step 4: Watch the checks to completion**

```bash
gh pr checks --watch
```
Expected: both `Backend CI / build-test` and `Frontend CI / build-test` finish **green**.

- [ ] **Step 5: Triage any failures (do not weaken the gate silently)**

If a check is red:
- Read the run log: `gh run view --log-failed` (or download the `backend-surefire-reports` artifact for backend test failures).
- If the failure is a **workflow bug** (wrong path, version, missing step) → fix the workflow file, commit, push; the checks re-run.
- If the failure is a **pre-existing problem in `main`'s code** (a real test/lint/build failure) → **report it to the user with the exact error**; do not disable the failing step or relax the gate without the user's decision.

- [ ] **Step 6: Report — do NOT merge**

Report the PR URL and the green/red status. Per the project rule, **do not merge** without the user's explicit go-ahead.

---

## Self-Review

**Spec coverage:**
- Two path-filtered workflows (backend + frontend) → Tasks 1 & 2. ✓
- Backend full suite incl. Testcontainers, JDK 21, no `services:` block → Task 1 Step 1. ✓
- Frontend Node 22, `npm ci`/lint/build/vitest, `working-directory: frontend` → Task 2 Step 1. ✓
- Triggers (PR + push to main, path filters incl. own file, `workflow_dispatch` backend-only) → both files. ✓
- `permissions: contents: read`, concurrency cancel-in-progress → both files. ✓
- Surefire artifact on failure → Task 1 Step 1. ✓
- Verification via real PR + watch + no-merge → Task 3. ✓
- Excluded items (arbitration, demo-agent, deploy, image build, coverage) → none added. ✓

**Placeholder scan:** No TBD/TODO; all YAML and commands are complete and literal. ✓

**Type/name consistency:** Workflow names `Backend CI` / `Frontend CI`, job id `build-test`, branch `worktree-ci+github-actions`, and artifact name `backend-surefire-reports` are used consistently across Tasks 1–3. ✓
