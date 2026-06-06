# Frontend (Next.js demo UI)

The `frontend/` app — **Next.js 16** (App Router) + TypeScript + Tailwind — is the **Client + Builder**
happy-path for the marketplace demo. It talks **only** to the Spring Boot API. Distilled from the build;
read before changing `frontend/`.

## How it reaches the API (no CORS)

`next.config.ts` rewrites `/api/:path*` → `${BACKEND_URL || 'http://localhost:8080'}/api/:path*`. The
browser calls **same-origin** `/api/...`; Next forwards to the backend and the `Authorization` header
passes through — so there is **no CORS config** on the backend. Set `BACKEND_URL` per environment.

## The one HTTP chokepoint — `lib/api.ts`

`api<T>(path, init?)` and `apiUpload<T>(path, formData)` are the two fetch helpers. `apiUpload` sends
a `multipart/form-data` request with the same JWT header — used by the builder image uploader.
`api<T>` is the only place that calls `fetch` for JSON requests. It:
- reads the JWT from `localStorage["hireai.token"]` and sets `Authorization: Bearer <jwt>`;
- calls `` `/api${path}` `` — **`path` has no `/api` prefix** (e.g. `api('/agents')`, `api('/auth/login')`);
- parses the `WebResult<T>` envelope `{ success, code, message, data }` and returns `data`;
- throws `ApiError{ code, message, status }` on `!success` / non-2xx;
- on **401** clears the token and redirects to `/login`; a **404** surfaces as an `ApiError` that
  `isPendingError(e)` recognises (the task-result endpoint 404s until the result exists, so the
  task-detail page keeps polling).

API types mirroring the backend DTOs live in `lib/types.ts` (incl. `TaskStatus` / `AgentStatus` /
`OutputFormat` string unions taken verbatim from the backend enums).

## Auth — `lib/auth.tsx`

`AuthProvider` + `useAuth()` expose `{ token, userId, role, login(email, password), logout() }`. Two
localStorage keys, kept in sync:
- **`hireai.token`** — the raw JWT (what `api()` reads);
- **`hireai.auth`** — the session `{ userId, role }` (NOT the token).

`login` POSTs `/auth/login` and writes both keys; state rehydrates in a mount effect (avoids an SSR
hydration mismatch). `components/RequireAuth` and `components/RoleGuard` are client guards; `RoleGuard`
reads `hireai.token` directly so it doesn't bounce an authenticated user before context rehydrates.

## Routes (`app/`)

- `login/` — email + password → `useAuth().login` → redirect by role (CLIENT→`/client`, BUILDER→`/builder`).
- `builder/` — portfolio dashboard; `builder/agents/new` — register an agent; `builder/agents/[id]` —
  manage console (tabs: Storefront · Pricing & tags · Stats · Reviews; image uploader via `apiUpload`).
- `client/` — **Marketplace** (search/category/sort/hot strip/agent grid); `client/tasks` — task list +
  wallet (resolution badges on each task row); `client/tasks/new` — auto-route submit; `client/tasks/[id]` — polls
  result; at `RESULT_RECEIVED` renders the `ResultReviewBar` (accept / reject with optional reason), then on
  `RESOLVED` shows the settled summary (payout/commission/refund amounts); `client/agents/[id]`
  — agent storefront; `client/agents/[id]/book` — direct-booking form (adopts agent's `output_spec`).

**`AgentDTO` is nested** — read `agent.currentVersion.{ capabilityCategories, price, webhookUrl }`, not the root.

## UI kit & tests

- `components/ui/` — `Button, Input, Select, Card, Field, Badge` (+ `statusColor(status)`); `Badge`
  takes a `status` prop and colours itself. `lib/outputSpecFields.tsx` is the shared output-spec sub-form.
- Tests: **Vitest + React Testing Library + MSW** — `npx vitest run` (~44 tests). Auth-dependent tests must seed
  **both** `hireai.token` and `hireai.auth`. `next build` and `npx tsc --noEmit` must stay clean.

## Run

`npm --prefix frontend run dev` (needs the backend on `:8080`). Full live stack: `docs/details/demo-runbook.md`.
Auth mechanics: `docs/details/identity-and-authz.md`. Next.js 16 has breaking changes vs older versions —
check the official Next 16 docs when an API differs from what you expect.

## Pending / demo-grade

Admin surface (not built); JWT in `localStorage` (httpOnly cookie is the hardening);
status via polling (no websockets).
