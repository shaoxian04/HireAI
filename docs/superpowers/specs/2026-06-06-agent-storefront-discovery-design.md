# Agent Storefront & Discovery — Design Spec

**Date:** 2026-06-06
**Scope:** Client-side agent **discovery** + an e-commerce-style agent **storefront** the builder
manages, plus **direct booking** of a chosen agent. Delivers **Module 6 (Discovery & Builder
Dashboard)** and the display half of **Module 5 (Reputation)**.
**Posture:** Hybrid — real & persistent where achievable; reviews/ratings **seeded** because a
legitimate "client rates a settled task" flow depends on Modules 4/5 (validation + settlement),
which are not built. Every seeded/limited surface is labelled honestly in the UI.

---

## 1. Goals & non-goals

**Goals**
1. A client can **discover** agents: a marketplace home with search (by agent **name** and
   **builder** name), browse **by category**, a "🔥 hot" featured strip, and sort.
2. A client can open an **agent storefront** showing what the agent does, its output contract, a
   sample deliverable, images, public stats, and reviews/ratings.
3. Two ways to submit a task: the existing **auto-routed** "submit new task", and **direct booking**
   ("Book this agent") from a storefront.
4. A builder can **manage an agent like a store page**: edit tagline/description/sample output,
   upload images, control listing visibility, edit pricing/turnaround/tags.
5. A builder can see **agent statistics**: volume & outcomes, performance, escrow earnings, and an
   activity feed + usage trend.

**Non-goals (scope cuts for the ~1-week window)**
- No agent **version-history UI** — pricing/tags edits mutate the current version in place.
- No **client-submitted reviews** — reviews are seeded only (no rating-after-settlement flow yet).
- No ML/semantic ranking — "hot" is a deterministic formula.
- No charting library — usage trend is a hand-rolled SVG sparkline.
- Routing stays **deterministic** (no AI). LLM is reserved for dispute arbitration only.

---

## 2. Architecture decisions (resolved during brainstorming)

| Decision | Choice |
|---|---|
| Build posture | **Hybrid** (real where cheap/high-value; reviews seeded) |
| Direct-submit semantics | **Direct booking / hard pin** — task goes only to the chosen agent; auto-matching skipped; client adopts the agent's `output_spec`; if no capacity → `AWAITING_CAPACITY` |
| Image storage | **Supabase Storage via backend** — backend uploads with the service-role key (server-side only), stores the public URL; public-read bucket |
| Storefront scope | All four bundles: branding & media, description & sample output, pricing/turnaround/tags, listing visibility |
| Builder stats | All four: volume & outcomes, performance, earnings (escrow), activity feed & usage trend |
| Discovery layout | **A — Marketplace grid** (search hero + category bar + hot strip + agent grid), filters folded into a top filter bar |
| Category discovery | First-class: category bar on the marketplace + `?category=` filter |

---

## 3. Data model

### 3.1 New migration V6 — `agent_profiles` (storefront content)
1:1 with `agents`; keeps marketing concern out of the core Agent aggregate.

```
agent_profiles
  agent_id        UUID  PK  FK -> agents(id)
  tagline         TEXT             -- short pitch
  description     TEXT             -- "what this agent does" (long, plain text/markdown)
  sample_output   TEXT             -- example deliverable (text or JSON string)
  logo_url        TEXT             -- Supabase Storage public URL
  cover_url       TEXT             -- Supabase Storage public URL
  gallery_urls    TEXT[] NOT NULL DEFAULT '{}'   -- screenshot URLs
  is_listed       BOOLEAN NOT NULL DEFAULT FALSE  -- visible in catalogue (independent of ACTIVE)
  is_featured     BOOLEAN NOT NULL DEFAULT FALSE  -- curated "hot" override
  gmt_create      TIMESTAMPTZ NOT NULL DEFAULT now()
  gmt_modified    TIMESTAMPTZ NOT NULL DEFAULT now()
```
A profile row is created (empty, `is_listed=false`) when an agent is registered, OR lazily on first
profile update — implementation chooses; default: **create on registration** so reads are simple.

An agent appears in the catalogue only when `agents.status = ACTIVE` **AND**
`agent_profiles.is_listed = true`.

### 3.2 New migration V7 — `reviews` (seeded)
Subset of the SAD `reviews` table; `task_id` nullable so seeded rows need not reference a resolved
task.

```
reviews
  id              UUID PK
  task_id         UUID NULL            -- nullable for seeded data (no UK while nullable)
  client_id       UUID NOT NULL        -- FK users(id); for seeded data, a demo client
  agent_id        UUID NOT NULL        -- FK agents(id)
  rating          SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5)
  review_text     TEXT
  builder_response TEXT NULL           -- builder's reply (editable from the manage page)
  is_published    BOOLEAN NOT NULL DEFAULT TRUE
  gmt_create      TIMESTAMPTZ NOT NULL DEFAULT now()
  gmt_modified    TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX (agent_id, gmt_create DESC)
```
Seed ~3–6 reviews per demo agent. Rating aggregate (`avg`, `count`) is computed from published rows.

### 3.3 Supabase Storage
- Bucket **`agent-media`**, **public read**, write only via backend service-role key.
- Object key convention: `agents/{agentId}/{kind}-{shortuuid}.{ext}` where `kind ∈ {logo,cover,gallery}`.
- Enable RLS / no public write policy (uploads never come from the browser).

### 3.4 No schema change needed
- `tasks.agent_version_id` + `category` already exist → per-agent stats join
  `tasks → agent_versions → agents`.
- `task_results.received_at` + `agent_status` → outcomes + turnaround.
- `agents.reputation_score` already exists → used as-is for ranking (not recomputed here).

---

## 4. Backend API

All endpoints sit behind the JWT security chain. Response envelope is the existing `WebResult<T>`.

### 4.1 Public catalogue (client-readable; authenticated; **no** ownership check)
New controller `CatalogueController` (`/api/catalogue`), backed by read services that only ever
return `ACTIVE + is_listed` agents.

- `GET /api/catalogue/agents?q=&category=&sort=&page=&size=`
  - `q` matches agent name **or** owner/builder name (case-insensitive contains).
  - `category` filters by `capability_categories` containment.
  - `sort ∈ {hot, rating, price_asc, price_desc, newest}`; default `hot`.
  - Returns `List<AgentCardDTO>`.
- `GET /api/catalogue/agents/{id}` → `AgentProfileDTO`. 404 if not `ACTIVE + is_listed`.
- `GET /api/catalogue/categories` → `List<CategoryCountDTO>` (`{ category, agentCount }`) over
  listed+active agents — powers the category bar.

`AgentCardDTO`: `id, name, builderName, tagline, logoUrl, coverUrl, categories[], price,
maxExecutionSeconds, reputationScore, ratingAvg, ratingCount, requestCount, isFeatured`.

`AgentProfileDTO`: card fields **plus** `description, sampleOutput, galleryUrls[],
outputSpec{format,schema,acceptanceCriteria}, stats{requestCount,successRate,avgTurnaroundSeconds},
reviews[]` (published, newest first; or fetched via a sub-call — embed for the demo).

### 4.2 Builder management (owner-checked — Invariant #5)
Extend `/api/agents`. Every handler resolves `ownerId` from the JWT and verifies the agent belongs
to the caller before mutating.

- `PUT /api/agents/{id}/profile` — body `{ tagline, description, sampleOutput, isListed }`.
- `POST /api/agents/{id}/media` — `multipart/form-data` `{ kind: logo|cover|gallery, file }`.
  Backend validates (see §6), uploads to Supabase Storage, persists URL (gallery appends), returns
  the updated profile.
- `DELETE /api/agents/{id}/media?kind=&url=` — removes a logo/cover/gallery entry (and best-effort
  deletes the storage object).
- `PUT /api/agents/{id}` — body `{ price, maxExecutionSeconds, capabilityCategories[] }`; updates
  the **current** agent version in place.
- `GET /api/agents/{id}/stats` → `AgentStatsDTO` (see §4.4).

### 4.3 Direct booking
Extend the task submit path rather than adding a parallel one (implemented as a separate endpoint `POST /api/tasks/direct` — see plan Phase 4 note).

- `POST /api/tasks` — add optional `agentId` to `SubmitTaskRequest`.
  - **If `agentId` present (direct booking):**
    1. Load the agent; require `ACTIVE` (+ `is_listed`).
    2. Resolve its `current_version_id` → version (`price`, `output_spec`, `max_execution_seconds`).
    3. Guard `budget >= price` (else reject with a clear `ResultCode`).
    4. **Adopt** the version's `output_spec` as the task's `output_spec` (client does not supply one).
    5. Freeze escrow (Invariant #1), set `tasks.agent_version_id = current_version_id`, **skip
       matching**, dispatch directly via the existing RabbitMQ → signed-webhook path.
    6. If the agent has no capacity → `AWAITING_CAPACITY` (existing off-path behaviour).
  - **If `agentId` absent:** unchanged auto-route (client supplies `category` + `output_spec`).

### 4.4 Builder stats (`AgentStatsDTO`)
Computed from real tasks routed to any version of the agent.
- **Volume & outcomes:** `total, completed (RESULT_RECEIVED|RESOLVED), failed (FAILED|TIMED_OUT|
  SPEC_VIOLATION), awaiting, successRate`.
- **Performance:** `avgTurnaroundSeconds` (`received_at − task.gmt_create`), `onTimeRate` (turnaround
  ≤ `max_execution_seconds`). Labelled "turnaround" (no separate dispatch timestamp).
- **Earnings (escrow):** `creditsInEscrow` (sum of budgets of this agent's open tasks),
  `potentialEarnings`. Clearly labelled "in escrow / pending — settlement is Module 5".
- **Activity & trend:** `recentTasks[]` (id, title, status, time) + `trend[]` (per-day request counts
  over the last 14 days) for the sparkline.

### 4.5 Ranking ("hot")
Deterministic score, computed in the read service:
```
hot = (reputationScore * 0.5)
    + (recentRequestCount_14d * 8)
    + (recencyBonus: 10 if a request in last 3d, else 0)
    + (isFeatured ? 1000 : 0)      -- curated pin always floats to top
```
Tunable constants; documented in code. `sort=rating|price_*|newest` bypass this.

---

## 5. Frontend (Next.js, Mission Control design system)

Reuse `globals.css` tokens, the restyled UI kit (`components/ui/*`), `AppShell`, adaptive `Nav`,
`api()`, and the auth guards. No slate/light styles. New `lib/types.ts` entries mirror the DTOs.

**Client**
- `/client` → **Marketplace** (layout A): search hero (`q`), category bar (from `/categories`), 🔥
  hot strip, sortable agent grid of **AgentCard** components. URL-driven (`?q=`, `?category=`,
  `?sort=`).
- `/client/agents/[id]` → **AgentStorefront** (matches the approved mock): hero/cover + logo, name +
  builder, rating + reputation, tagline, description, sample output, gallery, output contract,
  public stats, reviews; sticky **"Book this agent"** sidebar (price + turnaround).
- `/client/agents/[id]/book` → **direct-booking form**: title, description, budget (prefilled ≥
  price, validated), adopted `output_spec` shown read-only; submits `POST /api/tasks` with `agentId`.
- `/client/tasks` (existing list, moved here) · `/client/tasks/new` (auto-route, unchanged) ·
  `/client/tasks/[id]` (unchanged) · `/client/wallet`. Nav: `Marketplace · My Tasks · Wallet`.

**Builder**
- `/builder` → dashboard: portfolio summary tiles + per-agent cards (status, quick stats, "Manage").
- `/builder/agents/[id]` → **manage** (tabs): *Storefront* (tagline/description/sample/media uploader
  + listing toggle) · *Pricing & tags* · *Stats* (4 stat groups + sparkline + activity feed) ·
  *Reviews* (list + inline builder-response editor).
- `/builder/agents/new` (unchanged; may prefill an empty profile).

**Components (new):** `AgentCard`, `CategoryBar`, `HotStrip`, `RatingStars`, `MediaUploader`,
`StatTile`, `Sparkline`, `ReviewList`, `StorefrontEditor`. Keep files small (<300 lines).

---

## 6. Security

- Every builder mutation re-derives `ownerId` from the JWT and verifies agent ownership before any
  write (Invariant #5). Catalogue endpoints are client-readable but expose only `ACTIVE + is_listed`
  data (no owner-private fields, no webhook URLs).
- Supabase service-role key stays in `backend/.env` (git-ignored); **never** sent to the browser; no
  Supabase client/credentials in the frontend.
- Storage bucket is public-**read** only; uploads happen exclusively server-side.
- Image upload validation at the boundary: content-type ∈ `{image/png, image/jpeg, image/webp}`,
  size ≤ 2 MB, gallery capped (e.g. 6 images), filename/extension sanitised.
- Webhook URLs and other agent-private fields are excluded from all catalogue/profile DTOs.

---

## 7. Testing

- **Backend (JUnit + Testcontainers, target ≥80%):** catalogue list/search/category/sort + visibility
  gating; profile update + media upload **owner checks**; media validation; direct-booking guards
  (budget < price rejected, `output_spec` adoption, `agent_version_id` set, matching skipped); stats
  aggregation correctness. Integration tests skip cleanly when Docker is absent (existing convention).
- **Frontend (Vitest + RTL + MSW):** marketplace render/search/category/sort, storefront render,
  booking validation + submit, builder storefront edit + upload, stats render, reviews + response.
  **Keep the existing 22 tests green**; `tsc --noEmit` and `next build` clean.
- **Live E2E:** discover → open storefront → book → poll → result; builder edits storefront, uploads
  an image (appears on the public profile), and views real stats.

---

## 8. Build phases
1. **Migrations + storage** — V6 `agent_profiles`, V7 `reviews`, create `agent-media` bucket, seed
   profiles + reviews for demo agents.
2. **Catalogue read API + Marketplace + storefront page** — DTOs, `CatalogueController`, read
   services, ranking; `/client` marketplace + `/client/agents/[id]`.
3. **Direct booking** — extend `SubmitTaskRequest`/submit service; booking form.
4. **Builder storefront management** — profile/media/pricing endpoints; manage page Storefront +
   Pricing tabs; `MediaUploader` → Supabase Storage.
5. **Builder stats** — stats endpoint + service; Stats tab (tiles, sparkline, activity).
6. **Reviews** — seed + display on storefront + builder-response editor.
7. **Tests + live E2E** — backend + frontend suites, then the live run.

---

## 9. Open trade-offs (accepted)
- Editing price/tags mutates the current version in place (no new version) — acceptable for the demo;
  revisit if version history is needed.
- Reviews are seeded; ratings therefore reflect seed data, not earned reputation — labelled in UI.
- `creditsInEscrow` is shown instead of paid-out earnings because settlement (Module 5) is unbuilt —
  labelled in UI.
- `avgTurnaroundSeconds` approximates execution time (`received_at − created`); no dedicated dispatch
  timestamp is stored.

---

## 10. Note on version control
Committed on branch `feat/marketplace-spine`. The feature builds on the marketplace spine
(Modules 2+3 + auth) already on this branch.
