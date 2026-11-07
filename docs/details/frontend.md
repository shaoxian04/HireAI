# Frontend (Next.js demo UI)

The `frontend/` app — **Next.js 16** (App Router) + TypeScript + Tailwind — has three surfaces:
**Client**, **Builder** and **Admin**. It talks **only** to the Spring Boot API.

## How it reaches the API (no CORS)

`next.config.ts` rewrites `/api/:path*` → `${BACKEND_URL || 'http://localhost:8080'}/api/:path*`. The
browser calls **same-origin** `/api/...`; Next forwards to the backend and the `Authorization` header
passes through — so there is **no CORS config** on the backend. Set `BACKEND_URL` per environment.

For Google OAuth (backend-driven), the browser must reach the backend directly. Set
`NEXT_PUBLIC_API_ORIGIN` (default `http://localhost:8080`). `GoogleSignInButton` stores a random nonce in
`sessionStorage`, then navigates to `${NEXT_PUBLIC_API_ORIGIN}/oauth2/authorization/google?cb_nonce=<nonce>`.

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
`OutputFormat` string unions taken verbatim from the backend enums; `LoginResponse.roles: Role[]`;
`ApiKeyDTO` / `CreateApiKeyRequest` / `CreatedApiKeyDTO` for the API-key management page).

`api()` itself is **unchanged** by the backend's programmatic submission spine — the
`Authorization: ApiKey <key>` / `X-API-Key` credential is an alternative auth path for external client
agents calling the REST API directly; the browser always authenticates with the user's JWT
(`Authorization: Bearer <jwt>`).

## Auth — `lib/auth.tsx`

`AuthProvider` + `useAuth()` expose:

```
{ token, userId, roles, role, hasRole, activeSurface, setActiveSurface,
  login, register, becomeBuilder, loginWithToken, logout }
```

Three localStorage keys, kept in sync:
- **`hireai.token`** — the raw JWT (what `api()` reads);
- **`hireai.auth`** — the session `{ userId, roles }` (NOT the token);
- **`hireai.surface`** — the active surface (`"CLIENT"`, `"BUILDER"` or `"ADMIN"`), persisted across reloads.

`login` POSTs `/auth/login`; `register` POSTs `/auth/register {email, password, displayName?}`;
`becomeBuilder` POSTs `/auth/become-builder {acceptTerms:true}` — all three write all three keys and update
state. `loginWithToken(token)` stores a JWT received from the OAuth callback (returns `false` if the
token is unparsable or carries no roles). State rehydrates in a mount effect (avoids an SSR hydration
mismatch), which first clears all three keys if the JWT is expired or corrupt (`isExpiredJwt`). A backward-compatible `readPersisted` normalises the legacy `{role}` session shape into
`{roles:[role]}` so old localStorage sessions still work.

**`roles`** is the full `Role[]` set; **`hasRole(r)`** tests membership; **`activeSurface`** is the
currently displayed surface, read from `hireai.surface`; on login it is `CLIENT` if held, otherwise the
first role. **`setActiveSurface(r)`** updates it. **`role`** is a back-compat alias for `activeSurface`
(used by `Nav`).

## `lib/jwt.ts` — client-side decode

`decodeJwt(token): JwtClaims | null` base64-decodes the JWT payload and extracts `{ userId, roles, exp }`.
This is **not a signature verification** — only for UI gating. It tolerates both the new `roles` array
claim and the legacy `role` string claim. Returns `null` if the token is unparsable.

## Routes (`app/`)

- `/` — static marketing landing page.
- `login/` — email + password → `useAuth().login` → redirect by `homeFor` (`/client` if CLIENT, else
  `/builder`, else `/admin`, so dual-role users land on `/client`). Also shows `GoogleSignInButton`.
- `register/` — email + password + display name → `useAuth().register` → redirect to `/client`. Also
  shows `GoogleSignInButton`.
- `auth/callback/` — landing page for the backend OAuth success redirect
  (`/auth/callback#token=<jwt>&nonce=<nonce>`). Reads both from the URL fragment (never sent to a server)
  and requires the nonce to match `sessionStorage` (login-CSRF guard), then calls `loginWithToken`, scrubs
  the fragment with `replaceState`, and routes by role. A mismatch or any `?error=` param redirects to
  `/login?error=oauth`.
- `client/become-builder/` — terms acceptance page; calls `useAuth().becomeBuilder()` on confirm,
  which adds the `BUILDER` role and re-issues the token; switches `activeSurface` to `BUILDER` and
  routes to `/builder`.
- `builder/` — portfolio dashboard (wallet tile links to earnings); `builder/earnings` — earnings
  view (lifetime/pending totals from `GET /api/builder/earnings`, per-agent breakdown, payout
  history; amounts derived server-side from `SettlementPolicy`); `builder/agents/new` — register
  an agent; `builder/agents/[id]` — manage console (tabs: Storefront · Pricing · Stats ·
  **Reputation** · Reviews; image uploader via `apiUpload`). **Reputation** renders the owner-only
  breakdown from `GET /api/agents/{id}/reputation`: the score on a marked 0–100 scale (50 = the
  neutral start), Reliability and Satisfaction as separate meters, and the event stream with each
  event's contribution on the same 0–100 scale. The component's `priorWeight` and `weight` come from
  the server — **never restate kR/kS/α in the UI**, or the copy starts lying the moment the policy is
  tuned. **Reviews** shows the rating distribution, a needs-reply filter, and a collapsed reply
  editor that also *edits* an already-published reply (`PUT …/reviews/{id}/response` replaces).
- `client/` — **Marketplace** (search/category/sort/hot strip/agent grid); `client/tasks` — task list +
  wallet (resolution badges on each task row); `client/tasks/new` — **searchable category picker** (`CategoryCombobox` from `/catalogue/categories`, strict —
  Find-agents gated on a real category) → match-preview **shortlist → pick → book** at the
  agent's price (the `ShortlistPanel` opens in a `Modal` popout of ranked agent cards: profile avatars,
  best-match highlight, a plain-language `DeliveryRecord`, above-budget near-miss drawer; booking is
  `POST /tasks/direct`, so a near-miss pick pays above the typed budget; `localStorage` draft — **no reputation number and no stars**: the shortlist once divided the routing composite by 20
  and drew it as a five-star client rating, which is a different quantity entirely); `client/tasks/[id]` — polls
  result; at `PENDING_REVIEW` renders the `ResultReviewBar` (accept / reject with an A/B/C reason), then on
  `RESOLVED` shows the settled summary plus a dismissible `RatingPrompt` (`POST /api/tasks/{id}/review`,
  accepted-only and once per task — a rating left later beats one never given, so it must stay skippable).
  On a **terminal failure** (`SPEC_VIOLATION`/`TIMED_OUT`/`FAILED`/`CANCELLED`)
  it renders a `TaskFailurePanel` (plain-English cause + `{budget} cr refunded` line; the spec-violation panel
  fetches the real failing-check reason from `GET /api/tasks/{id}/validation`). **Once the task is in a dispute** the execution pipeline is replaced by a
  `DisputeProgressPanel` — a reject→arbitrator→admin **timeline** with Accept-ruling / Appeal actions while a
  proposed ruling awaits; it persists after `RESOLVED`. `client/disputes` — the client's dispute list ("awaiting
  your decision" `RULED` rows first); `client/keys` — API-key management (create, with a reveal-once `Modal`
  showing the raw key + copy button + a "won't see it again" warning; list; revoke); `client/webhooks` —
  webhooks console (register/replace an HTTPS callback per API key, reveal/rotate the signing secret, a
  delivery log with `DELIVERED`/`PENDING`/`DEAD` badges + an account-scoped `DEAD`-failure banner, manual
  resend); `client/agents/[id]` — agent storefront; `client/agents/[id]/book` — direct-booking form (adopts
  agent's `output_spec`). The `client/tasks/[id]` view also shows a per-task `WebhookDeliveryStatus`
  indicator with a resend action.
- `admin/` — overview plus read-only task, user-wallet and agent browsers; `admin/disputes` — the dispute queue; `admin/disputes/[id]` — dispute detail with
  the tier-2 human-backstop ruling.

**`AgentDTO` is nested** — read `agent.currentVersion.{ capabilityCategories, price, webhookUrl }`, not the root.

## Nav surface switcher

`components/Nav.tsx` reads `activeSurface`, `hasRole`, and `setActiveSurface` from `useAuth()`. When the
signed-in user holds **both** roles (`CLIENT` and `BUILDER`), a pill switcher renders inline — each
option is a `<Link>` (routes to that surface's home) that also calls `setActiveSurface` on click. Single-
role users see no switcher; the nav links shown depend on `activeSurface`:

- **CLIENT:** Marketplace · My tasks · API keys · Webhooks · Disputes. Disputes carries a count badge of
  disputes awaiting the client's decision (`RULED`), from `lib/useDisputeCount.ts` (`GET /api/disputes/mine`).
- **BUILDER:** My agents · Earnings.
- **ADMIN:** Overview · Disputes.

## Role guards

`components/RequireAuth` and `components/RoleGuard` are client guards. `RoleGuard` reads `hireai.token`
directly so it doesn't bounce an authenticated user before context rehydrates.

## UI kit & tests

- `components/ui/` — `Button, Input, Select, Card, Field, Badge, Modal` (+ `statusColor(status)`); `Badge`
  takes a `status` prop and colours itself; `Modal` is an accessible overlay dialog (focus-trap incl.
  `summary`/`select`/`textarea`, Esc-to-close, body scroll-lock). `components/CategoryCombobox.tsx` (strict
  searchable category picker) and `components/TaskFailurePanel.tsx` (per-failure panels) are feature components.
  `lib/outputSpecFields.tsx` is the shared output-spec sub-form. `components/manage/Tab*.tsx` are the
  builder manage-console panels; `components/DeliveryRecord.tsx` (client-facing delivery facts, no score)
  and `components/RatingPrompt.tsx` are the Module 5 client surfaces. `lib/useIdempotencyKey.ts` supplies the
  `Idempotency-Key` for both booking pages; `lib/taskStage.ts` maps `TaskStatus` to the six-stage pipeline.
- Tests: **Vitest + React Testing Library + MSW** — `npx vitest run`. `e2e/smoke.spec.ts` is not part of
  that run and needs Playwright, which is not in `package.json`. Auth-dependent tests
  must seed **both** `hireai.token` and `hireai.auth`. The gate is **all three** of `npm run lint`,
  `npx vitest run` and `npm run build` — CI enforces eslint, and `build` has caught a missing type
  import that lint and vitest both passed.

## Run

`npm --prefix frontend run dev` (needs the backend on `:8080`). Full live stack: `docs/details/demo-runbook.md`.
Auth mechanics: `docs/details/identity-and-authz.md`. Next.js 16 has breaking changes vs older versions —
check the official Next 16 docs when an API differs from what you expect.

## Pending / demo-grade

JWT in `localStorage` (httpOnly cookie is the hardening); status via polling (no websockets).
