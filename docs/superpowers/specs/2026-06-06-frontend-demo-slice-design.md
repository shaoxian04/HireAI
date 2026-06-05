# Frontend Demo Slice (Client + Builder) + Task Result Endpoint — Design Spec

**Date:** 2026-06-06
**Status:** Approved (design); decisions locked
**Branch:** `feat/marketplace-spine`

## Goal

A thin **Next.js** UI that drives the marketplace spine end-to-end for a demo: log in (JWT), a **BUILDER** registers + activates an AI agent, a **CLIENT** tops up + submits a task and watches it route → execute → **see the result**. Plus the one backend addition needed to display the result.

## Locked decisions

- **Scope:** Client + Builder happy-path only (Admin, public catalogue, SEO/SSR are out).
- **Result view:** add a backend `GET /api/tasks/{id}/result` so the UI shows the agent's actual output, not just status.
- **API access:** Next.js **rewrites** proxy `/api/*` → the backend (no backend CORS work).
- **Auth:** JWT in `localStorage` + a client-side auth context (demo-grade).

## Scope

**In:**
- **Backend:** `GET /api/tasks/{id}/result` (owner-checked) returning the `task_results` payload.
- **Frontend (`frontend/`, greenfield):** Next.js (App Router) + TypeScript + Tailwind; login; Builder agent list/register/activate; Client wallet/top-up + task submit + task detail with live status + result.

**Out:** registration UI (use seeded users / login only), Admin surface, public catalogue, websockets (use polling), refresh tokens, agent-side execution UI, mobile-specific layouts.

---

## Part A — Backend: task result endpoint

**Contract:** `GET /api/tasks/{taskId}/result` → `WebResult<TaskResultDTO>`
- `TaskResultDTO { UUID taskId, String agentStatus, String resultPayloadJson, String resultUrl, Instant receivedAt }`.
- **Identity (#5):** the caller (JWT) must be the task's `clientId`; otherwise the existing not-owner handling applies (404/403 per the current owner-check pattern — mirror `TaskController.getById`).
- **No result yet** (task not `RESULT_RECEIVED`): respond `404` with a `ResultCode` the UI treats as "pending" (keep polling).

**Implementation (mirror existing Task read path):**
- `TaskReadAppService.getResult(UUID taskId, UUID clientId)` → `TaskResultDTO` (loads the task, owner-checks, reads the `TaskResultModel` child through the root; throws not-found when absent).
- `controller/biz/task/dto/TaskResultDTO` + a `TaskResult2DTOConverter`; new `TaskController` method.
- **Tests:** an integration test (Testcontainers) — seed a task with a result → 200 with payload; another client → not-owner; no result → 404. A `@WebMvcTest` controller test (mirror existing, `@ActiveProfiles("test")`). No schema change (reads existing `task_results`).

---

## Part B — Frontend (Next.js App Router + TS + Tailwind)

### Architecture
- **Proxy, not CORS:** `next.config` rewrites `/api/:path*` → `${BACKEND_URL:-http://localhost:8080}/api/:path*`. The browser calls same-origin `/api/...`; the `Authorization` header passes through.
- **Auth context:** `AuthProvider` holds `{token, userId, role}`, persisted to `localStorage`. `useAuth()` exposes `login()`, `logout()`, and the identity. A client guard redirects to `/login` when there's no token; role guards keep CLIENT/BUILDER areas separate.
- **API client** (`lib/api.ts`): `api<T>(path, init)` reads the token, sets `Authorization: Bearer`, `fetch`es `/api/...`, parses the `WebResult<T>` envelope (`{success, code, message, data}`), throws `ApiError(code, message)` on `!success`/non-2xx, returns `data`. A `401` clears the token and redirects to `/login`; a `404` from the result endpoint is surfaced as a typed "pending" signal.
- **Live status:** the task detail page polls `GET /api/tasks/{id}` (every ~2s) and, once status is `RESULT_RECEIVED`, `GET /api/tasks/{id}/result`.
- **UI kit:** hand-rolled Tailwind components (`Button`, `Input`, `Select`, `Card`, `Badge`, `Field`); a top nav with app name + role + logout. Status badges colour-map the task lifecycle.

### Screens (≈7)
1. **`/login`** — email + password → `POST /api/auth/login` → store `{token, role}` → redirect (`CLIENT`→`/client`, `BUILDER`→`/builder`).
2. **`/builder`** — `GET /api/agents` (owner-scoped): cards with name, **status badge**, categories, price; "Register agent"; per-agent **Activate** (`POST /api/agents/{id}/activate`) when `PENDING_VERIFICATION`.
3. **`/builder/agents/new`** — form → `POST /api/agents`: `name`, `capabilityCategories` (tag input), **`webhookUrl`**, `maxExecutionSeconds`, `price`, `outputSpec{ format (Select over the backend `OutputFormat` enum), schema, acceptanceCriteria }` → back to `/builder`.
4. **`/client`** — `GET /api/wallet` (available + escrow balance) + **top-up** (`POST /api/wallet/topup {amount}`); `GET /api/tasks` list with status badges; "Submit task".
5. **`/client/tasks/new`** — form → `POST /api/tasks`: `title`, `description`, **`category`**, `budget`, `outputSpec{...}` → redirect to the new task's detail.
6. **`/client/tasks/[id]`** — `GET /api/tasks/{id}` (polled): title/budget/**status badge** through `SUBMITTED → QUEUED → EXECUTING → RESULT_RECEIVED` (or `AWAITING_CAPACITY`); when complete, `GET /api/tasks/{id}/result` → render `agentStatus`, pretty-printed `resultPayloadJson`, and `resultUrl`.

### API contracts (from the backend, for the typed client)
- `WebResult<T> { success, code, message, data }`.
- `POST /api/auth/login {email,password}` → `{token, userId, role}`.
- `GET /api/wallet` → `{walletId, availableBalance, escrowBalance}`; `POST /api/wallet/topup {amount}` → `WalletDTO`.
- `POST /api/agents {name, outputSpec{format,schema,acceptanceCriteria}, capabilityCategories[], webhookUrl, maxExecutionSeconds, price}` → `AgentDTO{id,ownerId,name,status,currentVersionId,reputationScore,currentVersion{...},createdAt}`; `POST /api/agents/{id}/activate` → `AgentDTO`; `GET /api/agents` → `AgentDTO[]`.
- `POST /api/tasks {title,description,category,budget,outputSpec{...}}` → `TaskDTO{id,clientId,title,description,budget,status,outputSpec,createdAt}`; `GET /api/tasks/{id}` → `TaskDTO`; `GET /api/tasks` → `TaskDTO[]`; `GET /api/tasks/{id}/result` → `TaskResultDTO` (Part A).

### How the demo lights up end-to-end
The builder registers an agent whose **`webhookUrl` is the running `demo-agent` stub**, with a `category` and `price`; activates it. The client submits a task with the **same `category`** and `budget ≥ price`. The backend routes → dispatches (RabbitMQ) → stub executes → token-authenticated callback → `RESULT_RECEIVED`, and the task page shows the result. This requires the live infra (RabbitMQ + stub + `allow-insecure-localhost` for an `http://localhost` webhook) — captured in a demo runbook delivered alongside.

## Testing
- **Backend:** the result endpoint gets an integration test (Testcontainers) + a controller test (`@ActiveProfiles("test")`); full suite stays green.
- **Frontend:** component/flow tests against a **mocked API (MSW)** for login, agent register/activate, task submit, and the task-detail status→result rendering (no live backend needed). A **Playwright happy-path smoke** (login → submit → status) is optional/time-permitting against a running stack. The full live walk is covered by the demo runbook (manual).

## Risks & mitigations
- **CORS** — avoided entirely by the rewrite proxy.
- **Full execution needs live infra** — the UI works against status regardless; the result view needs a completed task (runbook covers RabbitMQ + stub).
- **`OutputFormat` enum values** — the plan reads the backend enum so the `Select` matches exactly.
- **JWT in `localStorage`** (XSS surface) — acceptable for a demo; httpOnly-cookie storage is the hardening note.
- **Polling vs realtime** — polling is sufficient for the demo; websockets are out of scope.

## Decomposition (→ two plans)
1. **Backend: task result endpoint** (small; on `feat/marketplace-spine`).
2. **Frontend: Next.js Client + Builder app** (the bulk; `frontend/`, built against the contracts above, the result endpoint mockable).
