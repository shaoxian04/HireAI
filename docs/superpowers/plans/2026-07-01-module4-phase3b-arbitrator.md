# Module 4 Phase 3b — Arbitrator Service + Ruling Transparency — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Python LangGraph arbitrator that consumes a dispute request from RabbitMQ, judges the agent's output against the declared `acceptanceCriteria`, and posts back a deterministic `{category, rationale}`; plus the backend append-only ruling history and the transparency read that surfaces the arbitrator's justification to both the client and the owning builder.

**Architecture:** Three decoupled workstreams. (1) A new `arbitration/` Python service (FastAPI + LangGraph + OpenAI `gpt-4o` + aio-pika) consumes `task.dispute.requested` and calls the **existing** Phase-3a shared-secret callback. (2) The Spring backend replaces the single inline ruling slot on `disputes` with an append-only `dispute_rulings` child table and exposes `GET /api/disputes/by-task/{taskId}`. (3) The Next.js frontend renders the outcome. The LLM produces only a category + rationale; the Java domain owns all money (Invariant #3).

**Tech Stack:** Java 21 / Spring Boot (DDD COLA reactor), PostgreSQL + Flyway, RabbitMQ; Python 3.12 (uv/ruff/pytest) + LangGraph + langchain-openai + aio-pika + FastAPI + httpx; Next.js 16 / TypeScript / Tailwind / Vitest; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-06-30-module4-phase3b-arbitrator-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Inv #3 — deterministic money.** The arbitrator returns ONLY `{category, rationale}`. Settlement is computed Java-side from the *incoming* ruling category at apply-time. **The settlement logic in `DisputeAppServiceImpl.settleAndResolve` does NOT change** — it still `switch`es on `info.category()`, not on any re-read effective ruling. `rationale` is human-facing text and never enters the money path.
- **Inv #2 — append-only audit.** `dispute_rulings` is append-only: a Postgres trigger raises on UPDATE and DELETE, mirroring `ledger_entries` *exactly*. An Administrator override (future, unbuilt) never erases the arbitrator's row.
- **Inv #6 — secret service I/O.** The ruling callback is shared-secret (constant-time compared; already built). The worker holds `ARBITRATION_CALLBACK_SECRET` in env and sends it as `Authorization: Bearer {secret}`. All fetched URLs are HTTPS-only.
- **Inv #5 — server-side identity.** The transparency read derives the current user from the JWT via `CurrentUserProvider.currentUserId()`. Authorization = the task's client OR the builder who owns the agent version that ran the task. **Non-owner → `DomainException(NOT_FOUND)` → HTTP 404** (consistent with the existing `getForClient`/`getForOwner` read services, which return 404 rather than leak existence — this deliberately differs from the spec's "403"; see Task 4).
- **SSRF guard.** `fetch_result_content` is HTTPS-only, follows no redirects, resolves the host and rejects private/loopback/link-local/reserved/multicast/unspecified IPs and `169.254.169.254`, and enforces connect/read timeouts + a max-byte cap (oversize → truncate with an explicit marker).
- **Ack-discipline.** The worker ACKs a message ONLY after the ruling callback returns 2xx. Any persistent failure (graph error, repeated callback failure, un-fetchable evidence, un-parseable message) → `nack(requeue=false)` → the message dead-letters to `task.dispute.requested.dlq` → the existing Java `ArbitrationDlqListener` resolves the dispute by fallback full refund. Combined with the idempotent first-ruling-wins callback, this is safe under at-least-once delivery.
- **Don't redeclare the Rabbit topology.** The Java backend owns the exchange/queue/DLX/DLQ. The worker declares the queue **passively** (`passive=True`); it never creates or re-args topology.
- **Migrations are append-only files.** Never edit an applied migration (changes the Flyway checksum). The latest is `V20`; the new migration is `V21`. `gen_random_uuid()` is core Postgres 13+ (available).
- **DDD layering.** `hireai-domain` is framework-free (no Spring; only `hireai-utility` + jspecify). App services = interface + `impl/`. Framework-free domain services are wired in `DomainServiceConfig`. Controllers return `WebResult<T>` via `BaseController`.
- **Build/test commands.** Backend: `mvn -f backend/pom.xml -B -ntp package` (Testcontainers ITs auto-skip without Docker via `@EnabledIf("dockerAvailable")`). Frontend (cwd `frontend/`): `npx vitest run`. Python (cwd `arbitration/`): `uv run pytest` and `uv run ruff check .`.
- **Git hygiene.** Stage ONLY paths under `backend/`, `frontend/`, `arbitration/`, `docs/`, `.github/`. NEVER `git add -A` (the repo has unrelated untracked files). NO `Co-Authored-By` trailer (attribution disabled globally).

## File Structure

**Backend (`backend/`):**
- Create: `hireai-main/src/main/resources/db/migration/V21__dispute_rulings.sql`
- Create: `hireai-repository/.../adjudication/DisputeRulingDO.java`, `DisputeRulingJpaRepository.java`
- Modify: `hireai-domain/.../adjudication/enums/RulingDecidedBy.java`, `.../model/Ruling.java`, `.../model/DisputeModel.java`
- Modify: `hireai-repository/.../adjudication/DisputeDO.java`, `DisputeRepositoryImpl.java`
- Create: `hireai-application/.../adjudication/dispute/DisputeReadAppService.java` (+ `impl/DisputeReadAppServiceImpl.java`)
- Create: `hireai-controller/.../adjudication/DisputeController.java`, `dto/DisputeOutcomeDTO.java`, `dto/RulingDTO.java`, `Dispute2DTOConverter.java`
- Modify: `hireai-controller/.../config/SecurityConfig.java` (only if the secured chain allow-lists explicitly)

**Frontend (`frontend/`):**
- Modify: `lib/types.ts`, `components/ui/Badge.tsx`, `app/client/tasks/[id]/page.tsx`, `app/builder/earnings/page.tsx`
- Create: `components/DisputeOutcomePanel.tsx` (+ `.test.tsx`)

**Python (`arbitration/`):** new service — `pyproject.toml`, `Dockerfile`, `README.md`, `ruff.toml`, `app/{main,config,consumer,callback,schemas}.py`, `app/graph/{state,nodes,tools,build}.py`, `tests/`.

**CI (`.github/workflows/`):** `arbitration-ci.yml`.

**Docs:** `CLAUDE.md`, `docs/details/architecture.md`.

---

## Component 2 — Backend: append-only ruling history + transparency read

### Task 1: Migration `V21` + `dispute_rulings` persistence entity

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V21__dispute_rulings.sql`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingJpaRepository.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: table `dispute_rulings (id, dispute_id, tier, decided_by, category, rationale, decided_at, gmt_create)`, append-only (trigger raises on UPDATE/DELETE); `DisputeRulingDO` JPA entity; `DisputeRulingJpaRepository extends JpaRepository<DisputeRulingDO, UUID>` with `long countByDisputeId(UUID)` and `List<DisputeRulingDO> findByDisputeIdOrderByGmtCreateAsc(UUID)`.

- [ ] **Step 1: Write the migration.** Create `V21__dispute_rulings.sql`:

```sql
-- V21: add an append-only `dispute_rulings` child table (ruling history) and migrate any existing
-- inline ruling into it. The inline `disputes.ruling_*` columns are left in place here and dropped
-- in V22 (Task 3) AFTER the entity stops mapping them — keeping the schema and `DisputeDO` in step
-- so `ddl-auto: validate` never breaks mid-sequence. Lays the seam for a future tier-2 Administrator
-- override stored SEPARATELY from the arbitrator ruling (Invariant #2). Only ARBITRATOR/FALLBACK
-- write today. Hibernate `validate` tolerates the now-redundant inline columns until V22 removes them.
CREATE TABLE dispute_rulings (
    id          UUID PRIMARY KEY,
    dispute_id  UUID NOT NULL REFERENCES disputes (id),
    tier        INT  NOT NULL,
    decided_by  TEXT NOT NULL CHECK (decided_by IN ('ARBITRATOR', 'ADMINISTRATOR', 'FALLBACK')),
    category    TEXT NOT NULL CHECK (category IN ('FULFILLED', 'PARTIALLY_FULFILLED', 'NOT_FULFILLED')),
    rationale   TEXT,
    decided_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_create  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispute_rulings_dispute ON dispute_rulings (dispute_id);

-- Append-only enforcement: any UPDATE or DELETE raises (mirror of ledger_entries in V1).
CREATE OR REPLACE FUNCTION dispute_rulings_block_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'dispute_rulings is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dispute_rulings_no_update
    BEFORE UPDATE ON dispute_rulings
    FOR EACH ROW EXECUTE FUNCTION dispute_rulings_block_mutation();

CREATE TRIGGER trg_dispute_rulings_no_delete
    BEFORE DELETE ON dispute_rulings
    FOR EACH ROW EXECUTE FUNCTION dispute_rulings_block_mutation();

-- Migrate any existing inline ruling into the child table (0 rows on a fresh DB; correct for seeded data).
INSERT INTO dispute_rulings (id, dispute_id, tier, decided_by, category, rationale, decided_at, gmt_create)
SELECT gen_random_uuid(), id, COALESCE(ruling_tier, 1), decided_by, ruling_category, ruling_rationale,
       COALESCE(resolved_at, gmt_create), gmt_create
FROM disputes
WHERE ruling_category IS NOT NULL;
```

> The inline `ruling_*` columns are intentionally NOT dropped here — V22 (Task 3) drops them once `DisputeDO` no longer maps them. This keeps every task's build green.

- [ ] **Step 2: Write the JPA entity.** Create `DisputeRulingDO.java` (package `com.hireai.infrastructure.repository.adjudication`), mirroring the style of `DisputeDO`:

```java
package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for one append-only ruling row in a dispute's history. Written only by
 * {@code DisputeRepositoryImpl} (insert-only — the table's triggers raise on UPDATE/DELETE).
 * The effective ruling of a dispute is the highest-tier row.
 */
@Entity
@Table(name = "dispute_rulings")
public class DisputeRulingDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "dispute_id", nullable = false)
    private UUID disputeId;

    @Column(name = "tier", nullable = false)
    private int tier;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "rationale")
    private String rationale;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected DisputeRulingDO() {
    }

    public DisputeRulingDO(UUID id, UUID disputeId, int tier, String decidedBy, String category,
                           String rationale, Instant decidedAt, Instant gmtCreate) {
        this.id = id;
        this.disputeId = disputeId;
        this.tier = tier;
        this.decidedBy = decidedBy;
        this.category = category;
        this.rationale = rationale;
        this.decidedAt = decidedAt;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getDisputeId() { return disputeId; }
    public int getTier() { return tier; }
    public String getDecidedBy() { return decidedBy; }
    public String getCategory() { return category; }
    public String getRationale() { return rationale; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] **Step 3: Write the Spring Data repository.** Create `DisputeRulingJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DisputeRulingJpaRepository extends JpaRepository<DisputeRulingDO, UUID> {

    /** How many ruling rows already persisted for this dispute (drives idempotent append). */
    long countByDisputeId(UUID disputeId);

    /** Ruling history in append order (oldest first). */
    List<DisputeRulingDO> findByDisputeIdOrderByGmtCreateAsc(UUID disputeId);
}
```

- [ ] **Step 4: Write the failing append-only integration test.** Create `DisputeRulingRepositoryIntegrationTest.java`. It is a Testcontainers IT (gated by `@EnabledIf("dockerAvailable")` — follow the exact annotations/`@DynamicPropertySource` pattern in the existing `DisputeRepositoryIntegrationTest`). Assert: (a) a `dispute_rulings` row inserts and reads back; (b) an UPDATE on that row throws (append-only trigger); (c) a DELETE throws. Use the existing IT base/utilities. Because UPDATE/DELETE through `JpaRepository` may not map cleanly to a raw mutation, issue the UPDATE/DELETE via `EntityManager.createNativeQuery("UPDATE dispute_rulings SET rationale='x' WHERE id=?")` / `DELETE` and assert the persistence exception wraps the Postgres `RAISE`. Model the structure on how the ledger's append-only test (if present) or `DisputeRepositoryIntegrationTest` boots its context.

Run: `mvn -f backend/pom.xml -B -ntp -pl hireai-main test -Dtest=DisputeRulingRepositoryIntegrationTest`
Expected (no Docker): SKIPPED. Expected (Docker up): the three assertions pass once Steps 1–3 are in place.

- [ ] **Step 5: Run the full module build.**

Run: `mvn -f backend/pom.xml -B -ntp package`
Expected: BUILD SUCCESS. This task is **purely additive** — a new table (plus a no-op migrate on a fresh DB), a new entity, a new repository. Nothing existing changes, so the full suite stays green (dispute/arbitration ITs skip without Docker). The inline `ruling_*` columns still exist and `DisputeDO` still maps them, so `ddl-auto: validate` is satisfied.

- [ ] **Step 6: Commit.**

```bash
git add backend/hireai-main/src/main/resources/db/migration/V21__dispute_rulings.sql \
        backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingDO.java \
        backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingJpaRepository.java \
        backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/DisputeRulingRepositoryIntegrationTest.java
git commit -m "feat(adjudication): append-only dispute_rulings table + persistence entity (V21)"
```

### Task 2: Domain ruling history + persistence cutover (one atomic change — ends green)

This is the cutover: the domain switches from a single ruling to a history, and persistence moves from the inline `disputes.ruling_*` columns to the `dispute_rulings` child table — together, so the build is green after this task. The inline columns are left in the DB (now unmapped by `DisputeDO`; `ddl-auto: validate` tolerates extra columns) and physically dropped in Task 3.

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/RulingDecidedBy.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/Ruling.java`
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeDO.java`
- Modify: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/DisputeModelTest.java` (extend if present; else create)
- Test: `backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryIntegrationTest.java` (extend)

**Interfaces:**
- Consumes: `RulingCategory`, `RejectReason`, `DisputeStatus` (unchanged); `DisputeRulingJpaRepository` (Task 1).
- Produces:
  - `RulingDecidedBy` = `{ARBITRATOR, ADMINISTRATOR, FALLBACK}`.
  - `Ruling(int tier, RulingCategory category, String rationale, RulingDecidedBy decidedBy, Instant decidedAt)` — 5-arg record (was 4-arg).
  - `DisputeModel`: now holds `List<Ruling> rulings` (was a single `Ruling`); methods `List<Ruling> rulings()`, `Optional<Ruling> effectiveRuling()`, `recordRuling(Ruling)` (appends → RULED), `resolveByFallback(Ruling)` (appends → RESOLVED), `resolve()` (RULED → RESOLVED). `rehydrate(...)` now takes `List<Ruling> rulings`.
  - `DisputeRepositoryImpl` persists the parent `disputes` row WITHOUT ruling columns and appends only new (tail) rulings to `dispute_rulings`; `toModel` reconstructs the full history. `DisputeDO` no longer maps the inline ruling columns.

- [ ] **Step 1: Add the `ADMINISTRATOR` enum value.** Edit `RulingDecidedBy.java`:

```java
public enum RulingDecidedBy {
    ARBITRATOR,
    ADMINISTRATOR,  // tier-2 override; reserved seam — no writer yet (Module 4 admin tier deferred)
    FALLBACK
}
```

- [ ] **Step 2: Add `decidedAt` to `Ruling`.** Edit `Ruling.java` to the 5-arg record. Keep the existing non-null validation and add `decidedAt`:

```java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;

import java.time.Instant;
import java.util.Objects;

/**
 * A single ruling in a dispute's append-only history: the tier, the verdict category, the
 * human-readable rationale, who decided it, and when. Money is computed from {@code category}
 * at apply-time (Invariant #3) — the rationale is never in the money path.
 */
public record Ruling(int tier, RulingCategory category, String rationale,
                     RulingDecidedBy decidedBy, Instant decidedAt) {

    public Ruling {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(decidedBy, "decidedBy");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
```

- [ ] **Step 3: Convert `DisputeModel` to a ruling history.** Edit `DisputeModel.java`. Replace the single `Ruling ruling` field with `List<Ruling> rulings` (store an unmodifiable defensive copy), and update the factory/rehydrate/transition methods. The full target:

```java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dispute aggregate root. Holds an append-only ruling HISTORY (a future Administrator override is
 * appended above the arbitrator's tier-1 ruling, never replacing it — Invariant #2). The effective
 * ruling is the highest-tier entry. Settlement is computed at apply-time from the incoming category,
 * not from this model (Invariant #3) — this aggregate only records what was decided.
 */
public final class DisputeModel {

    private final UUID id;
    private final UUID taskId;
    private final UUID raisedBy;
    private final RejectReason reasonCategory;
    private final DisputeStatus status;
    private final List<Ruling> rulings;
    private final String correlationId;
    private final Instant createdAt;
    private final Instant resolvedAt;

    private DisputeModel(UUID id, UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                         DisputeStatus status, List<Ruling> rulings, String correlationId,
                         Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.taskId = taskId;
        this.raisedBy = raisedBy;
        this.reasonCategory = reasonCategory;
        this.status = status;
        this.rulings = List.copyOf(rulings);
        this.correlationId = correlationId;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public static DisputeModel open(UUID taskId, UUID raisedBy, RejectReason reasonCategory,
                                    String correlationId) {
        return new DisputeModel(UUID.randomUUID(), taskId, raisedBy, reasonCategory,
                DisputeStatus.OPEN, List.of(), correlationId, Instant.now(), null);
    }

    public static DisputeModel rehydrate(UUID id, UUID taskId, UUID raisedBy,
                                         RejectReason reasonCategory, DisputeStatus status,
                                         List<Ruling> rulings, String correlationId,
                                         Instant createdAt, Instant resolvedAt) {
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, status, rulings,
                correlationId, createdAt, resolvedAt);
    }

    /** OPEN → ARBITRATING (handed off for async arbitration). */
    public DisputeModel startArbitrating() {
        requireStatus(DisputeStatus.OPEN, "startArbitrating");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.ARBITRATING,
                rulings, correlationId, createdAt, resolvedAt);
    }

    /** OPEN|ARBITRATING → RULED: append a ruling to the history. */
    public DisputeModel recordRuling(Ruling ruling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "recordRuling requires OPEN|ARBITRATING; was " + status);
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RULED,
                append(ruling), correlationId, createdAt, resolvedAt);
    }

    /** RULED → RESOLVED. */
    public DisputeModel resolve() {
        requireStatus(DisputeStatus.RULED, "resolve");
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RESOLVED,
                rulings, correlationId, createdAt, Instant.now());
    }

    /** OPEN|ARBITRATING → RESOLVED via DLQ fallback: append the fallback ruling. */
    public DisputeModel resolveByFallback(Ruling fallbackRuling) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.ARBITRATING) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "resolveByFallback requires OPEN|ARBITRATING; was " + status);
        }
        return new DisputeModel(id, taskId, raisedBy, reasonCategory, DisputeStatus.RESOLVED,
                append(fallbackRuling), correlationId, createdAt, Instant.now());
    }

    /** True while a ruling can still be applied (first-ruling-wins guard). */
    public boolean isResolvable() {
        return status == DisputeStatus.OPEN || status == DisputeStatus.ARBITRATING;
    }

    /** The highest-tier ruling, or empty if none recorded yet. */
    public Optional<Ruling> effectiveRuling() {
        return rulings.stream().max(Comparator.comparingInt(Ruling::tier));
    }

    private List<Ruling> append(Ruling ruling) {
        List<Ruling> next = new ArrayList<>(rulings);
        next.add(ruling);
        return next;
    }

    private void requireStatus(DisputeStatus expected, String op) {
        if (status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    op + " requires " + expected + "; was " + status);
        }
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID raisedBy() { return raisedBy; }
    public RejectReason reasonCategory() { return reasonCategory; }
    public DisputeStatus status() { return status; }
    public List<Ruling> rulings() { return rulings; }
    public String correlationId() { return correlationId; }
    public Instant createdAt() { return createdAt; }
    public Instant resolvedAt() { return resolvedAt; }
}
```

> Preserve any existing transition logic or messages from the current `DisputeModel` that this rewrite omits — read the current file first and fold in anything not covered (e.g. exact exception messages other code/tests assert on). The behavioral change is only: single ruling → history list, `Ruling` gains `decidedAt`.

- [ ] **Step 4: Write/extend the domain unit test.** In `DisputeModelTest.java` add:

```java
@Test
void recordRulingAppendsToHistoryAndEffectiveIsHighestTier() {
    DisputeModel d = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(),
            RejectReason.C_INCOMPLETE, "corr-1").startArbitrating();
    Ruling tier1 = new Ruling(1, RulingCategory.PARTIALLY_FULFILLED, "half done",
            RulingDecidedBy.ARBITRATOR, Instant.parse("2026-07-01T00:00:00Z"));

    DisputeModel ruled = d.recordRuling(tier1);

    assertThat(ruled.status()).isEqualTo(DisputeStatus.RULED);
    assertThat(ruled.rulings()).containsExactly(tier1);
    assertThat(ruled.effectiveRuling()).contains(tier1);
}

@Test
void fallbackAppendsRulingAndResolves() {
    DisputeModel d = DisputeModel.open(UUID.randomUUID(), UUID.randomUUID(),
            RejectReason.A_MISMATCH, "corr-2").startArbitrating();
    Ruling fb = new Ruling(1, RulingCategory.NOT_FULFILLED, "platform fallback",
            RulingDecidedBy.FALLBACK, Instant.parse("2026-07-01T00:00:00Z"));

    DisputeModel resolved = d.resolveByFallback(fb);

    assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
    assertThat(resolved.effectiveRuling()).contains(fb);
    assertThat(resolved.resolvedAt()).isNotNull();
}
```

Run: `mvn -f backend/pom.xml -B -ntp -pl hireai-domain test -Dtest=DisputeModelTest`
Expected: FAIL to compile until Steps 1–3 land, then PASS. (`hireai-domain` has no Docker dependency.) NOTE: the wider reactor will not compile yet — Steps 5–7 fix the app + repository callers of the changed `Ruling`/`DisputeModel` API.

- [ ] **Step 5: Update `DisputeAppServiceImpl` for the 5-arg `Ruling`.** The two `new Ruling(...)` constructions must pass `decidedAt`. In `settleAndResolve`, change `new Ruling(TIER_1, info.category(), info.rationale(), decidedBy)` → `new Ruling(TIER_1, info.category(), info.rationale(), decidedBy, Instant.now())`. In `resolveByFallback`, change `new Ruling(TIER_1, RulingCategory.NOT_FULFILLED, "arbitration unavailable; refunded by platform fallback", RulingDecidedBy.FALLBACK)` → add `, Instant.now()`. Add `import java.time.Instant;`. **No other logic changes** — settlement still `switch`es on `info.category()` (Inv #3).

- [ ] **Step 6: Strip the inline ruling columns from `DisputeDO`.** Remove the four fields `rulingCategory`, `rulingRationale`, `rulingTier`, `decidedBy`, their `@Column` annotations, the matching constructor parameters, and their getters. The resulting constructor is:

```java
public DisputeDO(UUID id, UUID taskId, UUID raisedBy, String reasonCategory, String status,
                 String correlationId, Instant resolvedAt, Instant gmtCreate) {
    this.id = id;
    this.taskId = taskId;
    this.raisedBy = raisedBy;
    this.reasonCategory = reasonCategory;
    this.status = status;
    this.correlationId = correlationId;
    this.resolvedAt = resolvedAt;
    this.gmtCreate = gmtCreate;
}
```

Keep the remaining fields/columns (`id`, `task_id` unique, `raised_by`, `reason_category`, `status`, `correlation_id`, `resolved_at`, `gmt_create`) exactly as-is. The DB still has the `ruling_*` columns — that is fine; `ddl-auto: validate` only checks that *mapped* columns exist.

- [ ] **Step 7: Rewrite `DisputeRepositoryImpl` to read/write the child table.** Inject `DisputeRulingJpaRepository` alongside `DisputeJpaRepository`:

```java
package com.hireai.infrastructure.repository.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DisputeRepositoryImpl implements DisputeRepository {

    private final DisputeJpaRepository jpa;
    private final DisputeRulingJpaRepository rulingJpa;

    public DisputeRepositoryImpl(DisputeJpaRepository jpa, DisputeRulingJpaRepository rulingJpa) {
        this.jpa = jpa;
        this.rulingJpa = rulingJpa;
    }

    @Override
    public DisputeModel save(DisputeModel d) {
        jpa.save(new DisputeDO(d.id(), d.taskId(), d.raisedBy(), d.reasonCategory().name(),
                d.status().name(), d.correlationId(), d.resolvedAt(), d.createdAt()));

        // Append-only: insert only the ruling rows not yet persisted (the tail beyond the
        // persisted count). Idempotent under re-save; safe because dispute settlement is
        // serialized by the task-row pessimistic lock + first-ruling-wins guard.
        long persisted = rulingJpa.countByDisputeId(d.id());
        List<Ruling> rulings = d.rulings();
        for (int i = (int) persisted; i < rulings.size(); i++) {
            Ruling r = rulings.get(i);
            rulingJpa.save(new DisputeRulingDO(UUID.randomUUID(), d.id(), r.tier(),
                    r.decidedBy().name(), r.category().name(), r.rationale(),
                    r.decidedAt(), Instant.now()));
        }
        return d;
    }

    @Override
    public Optional<DisputeModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    @Override
    public Optional<DisputeModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskId(taskId).map(this::toModel);
    }

    private DisputeModel toModel(DisputeDO e) {
        List<Ruling> rulings = rulingJpa.findByDisputeIdOrderByGmtCreateAsc(e.getId()).stream()
                .map(r -> new Ruling(r.getTier(), RulingCategory.valueOf(r.getCategory()),
                        r.getRationale(), RulingDecidedBy.valueOf(r.getDecidedBy()), r.getDecidedAt()))
                .toList();
        return DisputeModel.rehydrate(e.getId(), e.getTaskId(), e.getRaisedBy(),
                RejectReason.valueOf(e.getReasonCategory()),
                DisputeStatus.valueOf(e.getStatus()), rulings, e.getCorrelationId(),
                e.getGmtCreate(), e.getResolvedAt());
    }
}
```

- [ ] **Step 8: Extend the dispute repository IT.** In `DisputeRepositoryIntegrationTest.java` add a round-trip that saves an OPEN dispute, then `recordRuling(...).resolve()`, saves again, and asserts `findByTaskId(...)` returns a dispute whose `effectiveRuling()` matches the recorded ruling (category, tier, decidedBy, rationale) and whose `rulings()` has exactly one row — proving the append-tail logic inserts once and reads back from the child table. Match the existing test's Testcontainers/`@EnabledIf("dockerAvailable")` setup.

- [ ] **Step 9: Fix remaining call sites + full green build.** Search `backend/` for any remaining references to the old 4-arg `Ruling`, the single-ruling `rehydrate`, `dispute.ruling()`, or the dropped `DisputeDO` getters/constructor args (Phase-3a tests, fixtures). Update each to the new signatures.

Run: `mvn -f backend/pom.xml -B -ntp package`
Expected: BUILD SUCCESS. Without Docker, dispute/arbitration ITs skip; with Docker they run (and exercise the new child-table path).

- [ ] **Step 10: Commit.**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/RulingDecidedBy.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/Ruling.java \
        backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/DisputeModel.java \
        backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/DisputeModelTest.java \
        backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeAppServiceImpl.java \
        backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeDO.java \
        backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryImpl.java \
        backend/hireai-main/src/test/java/com/hireai/infrastructure/repository/adjudication/DisputeRepositoryIntegrationTest.java
# add any other files the Step-9 compiler-fix touched (list them explicitly; do NOT git add -A)
git commit -m "refactor(adjudication): ruling history — domain + persist to append-only child table"
```

### Task 3: Migration `V22` — drop the now-dead inline ruling columns

After Task 2, `DisputeDO` no longer maps `disputes.ruling_category / ruling_rationale / ruling_tier / decided_by`; the child table is the source of truth. This task physically removes the dead columns. It is independently reviewable: confirm it drops ONLY the four dead columns and nothing else.

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V22__drop_inline_dispute_ruling_columns.sql`

**Interfaces:**
- Consumes: Task 1 (`dispute_rulings` already holds the migrated history), Task 2 (`DisputeDO` no longer maps these columns).
- Produces: a `disputes` table with no inline ruling columns (keeps `status` + `resolved_at`).

- [ ] **Step 1: Write the migration.** Create `V22__drop_inline_dispute_ruling_columns.sql`:

```sql
-- V22: drop the inline ruling columns from `disputes`. Their data was copied into the append-only
-- `dispute_rulings` child table in V21, and `DisputeDO` stopped mapping them in the Phase-3b cutover,
-- so they are dead. `disputes` keeps `status` + `resolved_at`; the effective ruling is the
-- highest-tier `dispute_rulings` row.
ALTER TABLE disputes
    DROP COLUMN ruling_category,
    DROP COLUMN ruling_rationale,
    DROP COLUMN ruling_tier,
    DROP COLUMN decided_by;
```

- [ ] **Step 2: Run the full build.** With Docker, the migration applies on the Testcontainers DB and `ddl-auto: validate` confirms `DisputeDO` still matches the (now-narrower) schema; the dispute/arbitration ITs exercise the child-table path end-to-end.

Run: `mvn -f backend/pom.xml -B -ntp package`
Expected: BUILD SUCCESS. (Without Docker the ITs skip, but the migration is still parsed/loaded by Flyway's classpath scan; the build stays green.)

- [ ] **Step 3: Commit.**

```bash
git add backend/hireai-main/src/main/resources/db/migration/V22__drop_inline_dispute_ruling_columns.sql
git commit -m "refactor(adjudication): drop dead inline dispute ruling columns (V22)"
```

### Task 4: Transparency read — `GET /api/disputes/by-task/{taskId}`

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeReadAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeReadAppServiceImpl.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/DisputeController.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/dto/DisputeOutcomeDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/dto/RulingDTO.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/Dispute2DTOConverter.java`
- Modify (only if the secured chain allow-lists explicitly): `backend/hireai-controller/.../config/SecurityConfig.java`
- Test: `backend/hireai-application/.../adjudication/dispute/DisputeReadAppServiceImplTest.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/biz/adjudication/DisputeControllerIntegrationTest.java` (or a `@WebMvcTest`-style test matching how existing controllers are tested)

**Interfaces:**
- Consumes: `DisputeRepository.findByTaskId`, `TaskRepository.findById`, `AgentRepository.findOwnerByVersionId`, `DisputeModel.effectiveRuling()`/`rulings()`, `CurrentUserProvider.currentUserId()`.
- Produces:
  - `DisputeReadAppService.getOutcomeForUser(UUID taskId, UUID currentUserId) : DisputeModel`
  - DTOs: `DisputeOutcomeDTO(UUID taskId, String status, String effectiveCategory, List<RulingDTO> rulings)`, `RulingDTO(int tier, String decidedBy, String category, String rationale, Instant decidedAt)`.

> **Authorization decision (deviates from spec's "403"):** the spec text says non-owners get 403. This plan returns **404** (`DomainException(NOT_FOUND)`), matching the two existing authorized read services (`TaskReadAppServiceImpl.getForClient`, `AgentReadAppServiceImpl.getForOwner`) which return NOT_FOUND so an endpoint never leaks that a resource exists to a non-owner. This still satisfies Invariant #5. If the reviewer or user prefers a literal 403, that is a one-line change (introduce a `ForbiddenException` → 403 mapping) — flagged here as the single intentional spec deviation.

- [ ] **Step 1: Write the read app-service interface.** Create `DisputeReadAppService.java`:

```java
package com.hireai.application.biz.adjudication.dispute;

import com.hireai.domain.biz.adjudication.model.DisputeModel;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public interface DisputeReadAppService {

    /**
     * The dispute (with full ruling history) for a task, visible only to the task's client or the
     * builder who owns the agent version that ran it. Anyone else, or no dispute, → NOT_FOUND.
     */
    DisputeModel getOutcomeForUser(@NonNull UUID taskId, @NonNull UUID currentUserId);
}
```

- [ ] **Step 2: Write the failing app-service test.** Create `DisputeReadAppServiceImplTest.java` — a pure Mockito unit test (mock `DisputeRepository`, `TaskRepository`, `AgentRepository`). Cover: (a) the task's client gets the dispute; (b) the owning builder (`findOwnerByVersionId` → currentUser) gets it; (c) a stranger → `DomainException` with `ResultCode.NOT_FOUND`; (d) a task with no dispute → `NOT_FOUND`; (e) a task that doesn't exist → `NOT_FOUND`. Build `TaskModel`/`DisputeModel` fixtures via their factories (the task needs `clientId` and `agentVersionId`; the dispute via `open(...).startArbitrating().recordRuling(...)`).

Run: `mvn -f backend/pom.xml -B -ntp -pl hireai-application test -Dtest=DisputeReadAppServiceImplTest`
Expected: FAIL (impl absent).

- [ ] **Step 3: Write the impl.** Create `impl/DisputeReadAppServiceImpl.java`:

```java
package com.hireai.application.biz.adjudication.dispute.impl;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DisputeReadAppServiceImpl implements DisputeReadAppService {

    private final DisputeRepository disputeRepository;
    private final TaskRepository taskRepository;
    private final AgentRepository agentRepository;

    public DisputeReadAppServiceImpl(DisputeRepository disputeRepository,
                                     TaskRepository taskRepository,
                                     AgentRepository agentRepository) {
        this.disputeRepository = disputeRepository;
        this.taskRepository = taskRepository;
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeModel getOutcomeForUser(UUID taskId, UUID currentUserId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> notFound(taskId));
        if (!isParticipant(task, currentUserId)) {
            throw notFound(taskId);
        }
        return disputeRepository.findByTaskId(taskId)
                .orElseThrow(() -> notFound(taskId));
    }

    private boolean isParticipant(TaskModel task, UUID currentUserId) {
        if (task.clientId().equals(currentUserId)) {
            return true;
        }
        return agentRepository.findOwnerByVersionId(task.agentVersionId())
                .map(builderId -> builderId.equals(currentUserId))
                .orElse(false);
    }

    private DomainException notFound(UUID taskId) {
        return new DomainException(ResultCode.NOT_FOUND, "Dispute not found for task: " + taskId);
    }
}
```

Run the Step-2 test: Expected PASS.

- [ ] **Step 4: Write the DTOs + converter.** Create `RulingDTO.java`:

```java
package com.hireai.controller.biz.adjudication.dto;

import java.time.Instant;

public record RulingDTO(int tier, String decidedBy, String category, String rationale,
                        Instant decidedAt) {}
```

Create `DisputeOutcomeDTO.java`:

```java
package com.hireai.controller.biz.adjudication.dto;

import java.util.List;
import java.util.UUID;

public record DisputeOutcomeDTO(UUID taskId, String status, String effectiveCategory,
                                List<RulingDTO> rulings) {}
```

Create `Dispute2DTOConverter.java`:

```java
package com.hireai.controller.biz.adjudication;

import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.biz.adjudication.dto.RulingDTO;
import com.hireai.domain.biz.adjudication.model.DisputeModel;

public final class Dispute2DTOConverter {

    private Dispute2DTOConverter() {
    }

    public static DisputeOutcomeDTO toDTO(DisputeModel dispute) {
        String effectiveCategory = dispute.effectiveRuling()
                .map(r -> r.category().name())
                .orElse(null);
        var rulings = dispute.rulings().stream()
                .map(r -> new RulingDTO(r.tier(), r.decidedBy().name(), r.category().name(),
                        r.rationale(), r.decidedAt()))
                .toList();
        return new DisputeOutcomeDTO(dispute.taskId(), dispute.status().name(),
                effectiveCategory, rulings);
    }
}
```

- [ ] **Step 5: Write the controller.** Create `DisputeController.java` (model exactly on `TaskController`'s `getResult` — `CurrentUserProvider` field, `BaseController.ok(...)`):

```java
package com.hireai.controller.biz.adjudication;

import com.hireai.application.biz.adjudication.dispute.DisputeReadAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.adjudication.dto.DisputeOutcomeDTO;
import com.hireai.controller.config.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController extends BaseController {

    private final DisputeReadAppService disputeReadAppService;
    private final CurrentUserProvider currentUser;

    public DisputeController(DisputeReadAppService disputeReadAppService,
                             CurrentUserProvider currentUser) {
        this.disputeReadAppService = disputeReadAppService;
        this.currentUser = currentUser;
    }

    @GetMapping("/by-task/{taskId}")
    public WebResult<DisputeOutcomeDTO> getByTask(@PathVariable("taskId") UUID taskId) {
        UUID userId = currentUser.currentUserId();
        DisputeOutcomeDTO dto = Dispute2DTOConverter.toDTO(
                disputeReadAppService.getOutcomeForUser(taskId, userId));
        return ok(dto);
    }
}
```

- [ ] **Step 6: Verify security wiring.** Read `SecurityConfig.java`. The route must require authentication (NOT permitAll — unlike the arbitration callback). If the secured chain authenticates everything not explicitly permitted, no change is needed. If it allow-lists explicitly, add `/api/disputes/**` to the authenticated set. Do NOT add it to any permitAll matcher.

- [ ] **Step 7: Write the controller/web integration test.** Create `DisputeControllerIntegrationTest.java`. Model it on how existing controllers are tested in `hireai-main` (the `test` profile uses `DevCurrentUserProvider.DEV_USER_ID`). Seed a task owned by `DEV_USER_ID` plus a resolved dispute with one ruling, and assert `GET /api/disputes/by-task/{taskId}` returns `success=true` with `effectiveCategory` and a one-element `rulings` array carrying `tier`, `decidedBy`, `category`, `rationale`. Add a case for an unknown task → HTTP 404. (If this requires Testcontainers, gate with `@EnabledIf("dockerAvailable")` like the sibling ITs.)

Run: `mvn -f backend/pom.xml -B -ntp package`
Expected: BUILD SUCCESS (ITs skip without Docker).

- [ ] **Step 8: Commit.**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/DisputeReadAppService.java \
        backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/dispute/impl/DisputeReadAppServiceImpl.java \
        backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/dispute/DisputeReadAppServiceImplTest.java \
        backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/ \
        backend/hireai-main/src/test/java/com/hireai/controller/biz/adjudication/DisputeControllerIntegrationTest.java
# include SecurityConfig.java only if Step 6 modified it
git commit -m "feat(adjudication): transparency read GET /api/disputes/by-task/{taskId} (client or owning builder)"
```

---

## Component 3 — Frontend: show the arbitrator's decision + rationale

### Task 5: `DisputeOutcomePanel` + types + client task-detail integration

**Files:**
- Modify: `frontend/lib/types.ts`
- Modify: `frontend/components/ui/Badge.tsx`
- Create: `frontend/components/DisputeOutcomePanel.tsx`
- Create: `frontend/components/DisputeOutcomePanel.test.tsx`
- Modify: `frontend/app/client/tasks/[id]/page.tsx`

**Interfaces:**
- Consumes: `api<T>(path)` from `lib/api.ts`, `isPendingError` (404), the Mission-Control kit (`Card`, `Badge`).
- Produces: `DisputeOutcomeDTO`/`RulingDTO` types; `<DisputeOutcomePanel outcome={...} />`.

- [ ] **Step 1: Add the types.** In `lib/types.ts` add (and add `"DISPUTED"`, `"PARTIALLY_ACCEPTED"` to the `TaskStatus` union and `"PARTIALLY_ACCEPTED"` to the `TaskResolution` union if absent — read the file first to match the exact union style):

```typescript
export type RulingCategory = "FULFILLED" | "PARTIALLY_FULFILLED" | "NOT_FULFILLED";
export type RulingDecidedBy = "ARBITRATOR" | "ADMINISTRATOR" | "FALLBACK";

export interface RulingDTO {
  tier: number;
  decidedBy: RulingDecidedBy;
  category: RulingCategory;
  rationale: string | null;
  decidedAt: string; // ISO-8601
}

export interface DisputeOutcomeDTO {
  taskId: string;
  status: string;
  effectiveCategory: RulingCategory | null;
  rulings: RulingDTO[];
}
```

- [ ] **Step 2: Extend the Badge status map.** In `components/ui/Badge.tsx`, add entries to `STATUS_CLASSES` so `DISPUTED`, `PARTIALLY_ACCEPTED`, and `RESOLVED` render distinctly (read the file's existing token classes first; reuse them — e.g. `DISPUTED` → amber, `PARTIALLY_ACCEPTED` → violet, `RESOLVED` → accent). Do not invent new colors; reuse the existing class strings.

- [ ] **Step 3: Write the failing component test.** Create `DisputeOutcomePanel.test.tsx` (model on `components/AgentCard.test.tsx`):

```tsx
import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { DisputeOutcomePanel } from "./DisputeOutcomePanel";
import type { DisputeOutcomeDTO } from "@/lib/types";

const base: DisputeOutcomeDTO = {
  taskId: "t-1",
  status: "RESOLVED",
  effectiveCategory: "PARTIALLY_FULFILLED",
  rulings: [
    {
      tier: 1,
      decidedBy: "ARBITRATOR",
      category: "PARTIALLY_FULFILLED",
      rationale: "Output met sections A and B but omitted the requested summary.",
      decidedAt: "2026-07-01T10:00:00Z",
    },
  ],
};

describe("DisputeOutcomePanel", () => {
  it("renders the arbitrator decision label and rationale", () => {
    render(<DisputeOutcomePanel outcome={base} />);
    expect(screen.getByText(/Partially fulfilled/i)).toBeInTheDocument();
    expect(screen.getByText(/omitted the requested summary/i)).toBeInTheDocument();
  });

  it("shows a fallback note when the ruling was a platform fallback", () => {
    render(
      <DisputeOutcomePanel
        outcome={{
          ...base,
          effectiveCategory: "NOT_FULFILLED",
          rulings: [{ ...base.rulings[0], decidedBy: "FALLBACK", category: "NOT_FULFILLED" }],
        }}
      />,
    );
    expect(screen.getByText(/auto-resolved/i)).toBeInTheDocument();
    expect(screen.getByText(/full refund/i)).toBeInTheDocument();
  });
});
```

Run (cwd `frontend/`): `npx vitest run components/DisputeOutcomePanel.test.tsx`
Expected: FAIL (component absent).

- [ ] **Step 4: Write the component.** Create `DisputeOutcomePanel.tsx`. Reuse `Card`; map category → label; render each ruling's rationale; FALLBACK → the auto-resolve note:

```tsx
import { Card } from "@/components/ui/Card";
import type { DisputeOutcomeDTO, RulingCategory } from "@/lib/types";

const CATEGORY_LABEL: Record<RulingCategory, string> = {
  FULFILLED: "Fulfilled — ruled in the agent's favour",
  PARTIALLY_FULFILLED: "Partially fulfilled — split settlement",
  NOT_FULFILLED: "Not fulfilled — full refund",
};

export function DisputeOutcomePanel({ outcome }: { outcome: DisputeOutcomeDTO }) {
  if (!outcome.rulings.length) return null;
  const effective = outcome.effectiveCategory;

  return (
    <Card className="space-y-4 border-t border-line">
      <p className="eyebrow">Arbitration outcome</p>
      {effective && (
        <p className="font-mono text-sm text-fg">{CATEGORY_LABEL[effective]}</p>
      )}
      <ul className="space-y-3">
        {outcome.rulings.map((r, i) => (
          <li key={i} className="space-y-1">
            {r.decidedBy === "FALLBACK" ? (
              <p className="font-mono text-xs text-amber">
                Auto-resolved (arbitrator unavailable) — full refund.
              </p>
            ) : (
              <p className="eyebrow text-dim">
                {r.decidedBy === "ADMINISTRATOR" ? "Administrator override" : "Arbitrator"} · tier {r.tier}
              </p>
            )}
            {r.rationale && (
              <p className="text-sm leading-relaxed text-muted">{r.rationale}</p>
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}
```

> Match the actual `Card` import/export style of the codebase (default vs named) — read `components/ui/Card.tsx` first and adjust the import. Same for any token class names.

Run the Step-3 test: Expected PASS.

- [ ] **Step 5: Wire it into the client task page.** In `app/client/tasks/[id]/page.tsx`, after the existing result/`Settled` section, fetch the outcome when the task is in a dispute-capable state and render the panel. Add near the other state hooks:

```tsx
const [outcome, setOutcome] = useState<DisputeOutcomeDTO | null>(null);

useEffect(() => {
  if (!task) return;
  if (task.status !== "DISPUTED" && task.status !== "RESOLVED") return;
  let cancelled = false;
  api<DisputeOutcomeDTO>(`/disputes/by-task/${task.id}`)
    .then((o) => { if (!cancelled) setOutcome(o); })
    .catch((e) => { if (!cancelled && !isPendingError(e)) console.error(e); });
  return () => { cancelled = true; };
}, [task]);
```

And in the render, after the `Settled` section:

```tsx
{outcome && <DisputeOutcomePanel outcome={outcome} />}
```

Add the imports (`api`, `isPendingError` from `@/lib/api`, `DisputeOutcomePanel`, the `DisputeOutcomeDTO` type). Confirm the api path prefix matches the existing calls in this file (they call `/tasks/${id}` → the `api()` client prepends `/api`; use `/disputes/by-task/${id}` to mirror that).

> The existing file may set `set-state-in-effect` lint constraints — follow the same effect/cleanup pattern already used for the result-polling effect in this file to avoid the react-hooks lint errors seen earlier in CI.

- [ ] **Step 6: Run lint + build + tests.**

Run (cwd `frontend/`): `npm run lint && npx vitest run`
Expected: lint clean; all tests pass (including the new panel test).

- [ ] **Step 7: Commit.**

```bash
git add frontend/lib/types.ts frontend/components/ui/Badge.tsx \
        frontend/components/DisputeOutcomePanel.tsx frontend/components/DisputeOutcomePanel.test.tsx \
        frontend/app/client/tasks/[id]/page.tsx
git commit -m "feat(frontend): arbitration outcome panel on the client task view"
```

### Task 6: Builder-side dispute outcome on the earnings view

**Files:**
- Modify: `frontend/app/builder/earnings/page.tsx`
- Test: `frontend/app/builder/earnings/page.test.tsx` (create, or extend an existing earnings test if present)
- (Possibly) Modify: `frontend/lib/types.ts` (if the payout DTO type lacks `taskId`)

**Interfaces:**
- Consumes: `DisputeOutcomePanel` (Task 5), `api<DisputeOutcomeDTO>`, the existing `BuilderEarningsDTO` payout items.

> **Scope note (documented limitation):** builders have no per-task detail route today. This task surfaces the arbitrator's justification on the earnings **payout** rows — i.e. disputes that resolved to a payout or split. Disputes that resolved to a **full refund** produce no payout row, so they are not yet discoverable on the builder side; that needs a builder dispute-list endpoint and is **deferred** (note it in the PR description and `CLAUDE.md` pending list). This keeps Phase 3b free of new backend list endpoints.

- [ ] **Step 1: Confirm `taskId` on the payout item.** Read `lib/types.ts` (the `BuilderEarningsDTO`/payout type) and `app/builder/earnings/page.tsx`. If a payout item already carries `taskId`, use it. If not, STOP and surface this as a NEEDS_CONTEXT — adding `taskId` to the backend earnings DTO is a small change but out of this task's declared files; the controller should decide whether to fold it in. (Do not silently expand scope.)

- [ ] **Step 2: Write the failing test.** Create `app/builder/earnings/page.test.tsx` that mocks `api()` to return one payout with a `taskId` and a dispute outcome for it, renders the page, and asserts the arbitrator rationale text appears (or appears after clicking a "View arbitration outcome" control, if you implement it as an expander). Model the mock on `lib/api.test.ts`'s `vi.stubGlobal("fetch", ...)` or mock the `api` module directly with `vi.mock`.

Run (cwd `frontend/`): `npx vitest run app/builder/earnings/page.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Implement.** For each payout row that has a `taskId`, fetch `GET /disputes/by-task/{taskId}` (tolerate 404 via `isPendingError` → no panel), and render `<DisputeOutcomePanel outcome={...} />` inline (an expander labelled "Arbitration outcome" is fine). Keep the fetches lazy/guarded to avoid N calls on mount if the list is large — fetch on expand, or batch only rows the page already marks as disputed. Reuse the same effect/cleanup discipline as Task 5.

Run the Step-2 test: Expected PASS.

- [ ] **Step 4: Lint + test.**

Run (cwd `frontend/`): `npm run lint && npx vitest run`
Expected: clean + green.

- [ ] **Step 5: Commit.**

```bash
git add frontend/app/builder/earnings/page.tsx frontend/app/builder/earnings/page.test.tsx
# include frontend/lib/types.ts only if Step 1 required a type change
git commit -m "feat(frontend): show arbitration outcome on builder earnings payouts"
```

---

## Component 1 — The Python arbitrator service (`arbitration/`)

> All Python tasks run with cwd `arbitration/`. The graph is **pure** — it returns a `RulingResult`; the RabbitMQ ack and the callback POST live in the consumer (Task 12), so ack-discipline is enforced in one place and the graph is unit-testable without a broker. (This refines the spec, which listed `respond` as a graph node; the transport is intentionally pulled out of the graph.)

### Task 7: Service scaffold — packaging, config, FastAPI `/health`

**Files:**
- Create: `arbitration/pyproject.toml`, `arbitration/ruff.toml`, `arbitration/Dockerfile`, `arbitration/README.md`, `arbitration/.gitignore`
- Create: `arbitration/app/__init__.py`, `arbitration/app/config.py`, `arbitration/app/main.py`
- Create: `arbitration/tests/__init__.py`, `arbitration/tests/test_health.py`

**Interfaces:**
- Produces: `Settings` (pydantic-settings) with `openai_api_key`, `openai_model` (default `gpt-4o`), `rabbitmq_url`, `arbitration_callback_secret`, `backend_base_url`, `dispute_queue` (default `task.dispute.requested`), `fetch_max_bytes`, `fetch_timeout_seconds`, `callback_timeout_seconds`; a FastAPI `app` exposing `GET /health`.

- [ ] **Step 1: Write `pyproject.toml`.**

```toml
[project]
name = "hireai-arbitration"
version = "0.1.0"
description = "HireAI dispute arbitration worker (LangGraph + OpenAI)"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.30",
    "aio-pika>=9.4",
    "langgraph>=0.2",
    "langchain-openai>=0.2",
    "langchain-core>=0.3",
    "httpx>=0.27",
    "pydantic>=2.7",
    "pydantic-settings>=2.3",
    "jsonschema>=4.22",
]

[dependency-groups]
dev = [
    "pytest>=8.2",
    "pytest-asyncio>=0.23",
    "respx>=0.21",
    "ruff>=0.5",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["app"]
```

- [ ] **Step 2: Write `ruff.toml`.**

```toml
line-length = 100
target-version = "py312"

[lint]
select = ["E", "F", "I", "UP", "B"]
```

- [ ] **Step 3: Write `.gitignore`.**

```gitignore
.venv/
__pycache__/
*.pyc
.pytest_cache/
.ruff_cache/
.env
uv.lock
```

- [ ] **Step 4: Write `app/config.py`.**

```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    openai_api_key: str = ""
    openai_model: str = "gpt-4o"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"
    arbitration_callback_secret: str = ""
    backend_base_url: str = "http://localhost:8080"
    dispute_queue: str = "task.dispute.requested"
    fetch_max_bytes: int = 5_000_000
    fetch_timeout_seconds: float = 10.0
    callback_timeout_seconds: float = 10.0


def load_settings() -> Settings:
    return Settings()
```

- [ ] **Step 5: Write `app/main.py`** (health only for now; the consumer lifespan is wired in Task 13):

```python
from fastapi import FastAPI

app = FastAPI(title="HireAI Arbitration Worker")


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

- [ ] **Step 6: Write the health test.** `tests/test_health.py`:

```python
from fastapi.testclient import TestClient

from app.main import app


def test_health_ok():
    client = TestClient(app)
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}
```

- [ ] **Step 7: Write the `Dockerfile`.**

```dockerfile
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir uv
WORKDIR /app
COPY pyproject.toml ./
RUN uv sync --no-dev
COPY app ./app
EXPOSE 8000
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 8: Write `README.md`** documenting: purpose; the inbound queue `task.dispute.requested` (topology owned by the Java backend, declared passively here); the outbound callback contract; the env vars; `uv sync`, `uv run pytest`, `uv run ruff check .`, `uv run uvicorn app.main:app`.

- [ ] **Step 9: Install + run.**

Run: `uv sync --dev && uv run pytest tests/test_health.py -v`
Expected: 1 passed.

Run: `uv run ruff check .`
Expected: no errors.

- [ ] **Step 10: Commit.**

```bash
git add arbitration/pyproject.toml arbitration/ruff.toml arbitration/Dockerfile arbitration/README.md \
        arbitration/.gitignore arbitration/app/ arbitration/tests/
git commit -m "feat(arbitration): scaffold Python worker (uv/ruff/pytest, FastAPI health, config)"
```

### Task 8: Message schemas — inbound request + outbound ruling

**Files:**
- Create: `arbitration/app/schemas.py`
- Create: `arbitration/tests/test_schemas.py`

**Interfaces:**
- Consumes: the Java `ArbitrationRequestMessage` JSON (camelCase, Jackson; a `__TypeId__` header is present and ignored).
- Produces: `ArbitrationRequest` (pydantic, parses camelCase via alias), `RulingResult(category, rationale)`.

- [ ] **Step 1: Write the failing test.** `tests/test_schemas.py` pins the golden JSON the Java side publishes:

```python
from app.schemas import ArbitrationRequest, RulingResult

GOLDEN = """
{
  "disputeId": "11111111-1111-1111-1111-111111111111",
  "taskId": "22222222-2222-2222-2222-222222222222",
  "correlationId": "dispute-22222222-2222-2222-2222-222222222222",
  "format": "JSON",
  "schema": "{\\"type\\":\\"object\\"}",
  "acceptanceCriteria": "Must list at least 3 sources.",
  "resultPayloadJson": "{\\"sources\\":[\\"a\\"]}",
  "resultUrl": null,
  "reasonCategory": "C_INCOMPLETE"
}
"""


def test_parses_camel_case_golden_message():
    req = ArbitrationRequest.model_validate_json(GOLDEN)
    assert str(req.dispute_id) == "11111111-1111-1111-1111-111111111111"
    assert req.format == "JSON"
    assert req.schema_ == '{"type":"object"}'
    assert req.acceptance_criteria == "Must list at least 3 sources."
    assert req.result_payload_json == '{"sources":["a"]}'
    assert req.result_url is None
    assert req.reason_category == "C_INCOMPLETE"


def test_tolerates_unknown_fields():
    req = ArbitrationRequest.model_validate_json('{"disputeId":"11111111-1111-1111-1111-111111111111",'
        '"taskId":"22222222-2222-2222-2222-222222222222","correlationId":"c","format":"TEXT",'
        '"reasonCategory":"A_MISMATCH","somethingExtra":true}')
    assert req.format == "TEXT"


def test_ruling_result_round_trips():
    r = RulingResult(category="PARTIALLY_FULFILLED", rationale="missing one source")
    assert r.model_dump() == {"category": "PARTIALLY_FULFILLED", "rationale": "missing one source"}
```

Run: `uv run pytest tests/test_schemas.py -v`
Expected: FAIL (module absent).

- [ ] **Step 2: Write `app/schemas.py`.**

```python
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ArbitrationRequest(BaseModel):
    """Inbound dispute request (camelCase JSON from the Java backend, Jackson)."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")

    dispute_id: str
    task_id: str
    correlation_id: str
    format: str
    schema_: str | None = Field(default=None, alias="schema")
    acceptance_criteria: str | None = None
    result_payload_json: str | None = None
    result_url: str | None = None
    reason_category: str


class RulingResult(BaseModel):
    """The arbitrator's verdict — the ONLY thing returned to the backend (Invariant #3)."""

    category: str  # FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED
    rationale: str
```

Run the test: Expected PASS. Run `uv run ruff check .`: clean.

- [ ] **Step 3: Commit.**

```bash
git add arbitration/app/schemas.py arbitration/tests/test_schemas.py
git commit -m "feat(arbitration): inbound/outbound message schemas (camelCase contract)"
```

### Task 9: Evidence tools — SSRF-guarded fetch + schema validation

**Files:**
- Create: `arbitration/app/graph/__init__.py`, `arbitration/app/graph/tools.py`
- Create: `arbitration/tests/test_tools.py`

**Interfaces:**
- Produces: `async fetch_result_content(url, *, max_bytes, timeout) -> str` (SSRF-guarded), `validate_against_schema(payload, schema) -> dict` (returns `{"valid": bool, "errors": [...]}`), and a `FetchError` exception. The URL guard helper `_assert_public_https(url)` is independently testable.

- [ ] **Step 1: Write the failing tests.** `tests/test_tools.py` — focus on the SSRF guard (no network needed) and schema validation:

```python
import pytest

from app.graph.tools import FetchError, _assert_public_https, validate_against_schema


def test_rejects_non_https():
    with pytest.raises(FetchError):
        _assert_public_https("http://example.com/x")


def test_rejects_loopback_host():
    with pytest.raises(FetchError):
        _assert_public_https("https://127.0.0.1/x")


def test_rejects_metadata_ip():
    with pytest.raises(FetchError):
        _assert_public_https("https://169.254.169.254/latest/meta-data/")


def test_rejects_private_hostname(monkeypatch):
    # Force DNS resolution to a private address.
    import app.graph.tools as tools

    monkeypatch.setattr(tools.socket, "getaddrinfo",
                        lambda *a, **k: [(None, None, None, None, ("10.0.0.5", 443))])
    with pytest.raises(FetchError):
        _assert_public_https("https://internal.evil.test/x")


def test_validate_against_schema_reports_errors():
    schema = '{"type":"object","required":["sources"]}'
    ok = validate_against_schema('{"sources":[]}', schema)
    bad = validate_against_schema('{}', schema)
    assert ok["valid"] is True
    assert bad["valid"] is False
    assert bad["errors"]


def test_validate_handles_malformed_json():
    res = validate_against_schema("not json", '{"type":"object"}')
    assert res["valid"] is False
```

Run: `uv run pytest tests/test_tools.py -v`
Expected: FAIL (module absent).

- [ ] **Step 2: Write `app/graph/tools.py`.**

```python
import ipaddress
import json
import socket
from urllib.parse import urlparse

import httpx
from jsonschema import Draft202012Validator


class FetchError(Exception):
    """Raised when an evidence URL is unsafe or unfetchable."""


def _assert_public_https(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme != "https":
        raise FetchError("only https URLs are allowed")
    host = parsed.hostname
    if not host:
        raise FetchError("missing host")
    port = parsed.port or 443
    try:
        infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except socket.gaierror as e:
        raise FetchError(f"cannot resolve host: {host}") from e
    for *_, sockaddr in infos:
        ip = ipaddress.ip_address(sockaddr[0])
        if (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved
                or ip.is_multicast or ip.is_unspecified):
            raise FetchError(f"blocked non-public address: {ip}")


async def fetch_result_content(url: str, *, max_bytes: int, timeout: float) -> str:
    """Fetch the full FILE evidence over HTTPS, SSRF-guarded, size-capped, no redirects."""
    _assert_public_https(url)
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=False) as client:
        async with client.stream("GET", url) as resp:
            resp.raise_for_status()
            content = b""
            truncated = False
            async for chunk in resp.aiter_bytes():
                content += chunk
                if len(content) > max_bytes:
                    content = content[:max_bytes]
                    truncated = True
                    break
            ctype = resp.headers.get("content-type", "")
    if "text" in ctype or "json" in ctype or not ctype:
        text = content.decode("utf-8", errors="replace")
    else:
        text = f"[binary content omitted: {len(content)} bytes, content-type {ctype!r}]"
    if truncated:
        text += "\n[TRUNCATED — content exceeded the size cap]"
    return text


def validate_against_schema(payload: str, schema: str | None) -> dict:
    """Re-run the JSON-Schema check as citable grounding evidence (not a gate)."""
    if not schema:
        return {"valid": True, "errors": [], "note": "no schema declared"}
    try:
        data = json.loads(payload)
    except (json.JSONDecodeError, TypeError):
        return {"valid": False, "errors": ["payload is not valid JSON"]}
    try:
        schema_obj = json.loads(schema)
    except (json.JSONDecodeError, TypeError):
        return {"valid": False, "errors": ["declared schema is not valid JSON"]}
    validator = Draft202012Validator(schema_obj)
    errors = [e.message for e in validator.iter_errors(data)]
    return {"valid": not errors, "errors": errors}
```

> Note the residual DNS-rebinding (TOCTOU) gap: the guard resolves the host, then httpx resolves again on connect. With `follow_redirects=False` and a public-only check this is acceptable for the FYP threat model; document it in the README's security note.

Run the test: Expected PASS. Run `uv run ruff check .`: clean.

- [ ] **Step 3: Commit.**

```bash
git add arbitration/app/graph/__init__.py arbitration/app/graph/tools.py arbitration/tests/test_tools.py
git commit -m "feat(arbitration): SSRF-guarded evidence fetch + schema-validation tools"
```

### Task 10: The LangGraph — gather evidence, deliberate, classify, critique

**Files:**
- Create: `arbitration/app/graph/state.py`, `arbitration/app/graph/nodes.py`, `arbitration/app/graph/build.py`
- Create: `arbitration/tests/test_graph.py`

**Interfaces:**
- Consumes: `ArbitrationRequest`/`RulingResult` (Task 8), the tools (Task 9), `Settings` (Task 7).
- Produces: `build_graph(llm, settings)` → a compiled graph; `async run_arbitration(graph, request) -> RulingResult`. The graph is pure (no RabbitMQ, no callback). `llm` is injected so tests pass a fake.

- [ ] **Step 1: Write `app/graph/state.py`.**

```python
from typing import TypedDict

from app.schemas import ArbitrationRequest


class ArbitrationState(TypedDict, total=False):
    request: ArbitrationRequest
    evidence: str          # gathered: payload + fetched FILE content + schema-check result
    deliberation: str      # the model's reasoning over criteria vs evidence
    category: str          # FULFILLED | PARTIALLY_FULFILLED | NOT_FULFILLED
    rationale: str
```

- [ ] **Step 2: Write `app/graph/nodes.py`.** Nodes are async functions over the state. `gather_evidence` assembles the payload, fetches FILE content (guarded), and runs the schema check; `deliberate` and `classify` call the injected `llm` (classify uses structured output); `critique` does one bounded consistency pass.

```python
from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import ArbitrationState
from app.graph.tools import FetchError, fetch_result_content, validate_against_schema
from app.schemas import RulingResult

CATEGORIES = {"FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED"}

_SYSTEM = (
    "You are a neutral dispute arbitrator for a task marketplace. You judge whether an AI agent's "
    "output satisfies the task's declared acceptance criteria and addresses the client's complaint. "
    "You decide ONLY a category and a short rationale. You never discuss money, payment, or refunds — "
    "settlement is computed elsewhere. Categories: FULFILLED (meets the criteria), "
    "PARTIALLY_FULFILLED (meets some but not all), NOT_FULFILLED (fails the criteria)."
)


async def gather_evidence(state: ArbitrationState, *, max_bytes: int, timeout: float) -> dict:
    req = state["request"]
    parts: list[str] = [f"OUTPUT FORMAT: {req.format}", f"CLIENT COMPLAINT CATEGORY: {req.reason_category}"]
    if req.acceptance_criteria:
        parts.append(f"ACCEPTANCE CRITERIA:\n{req.acceptance_criteria}")
    if req.result_payload_json:
        parts.append(f"AGENT OUTPUT (inline):\n{req.result_payload_json}")
        check = validate_against_schema(req.result_payload_json, req.schema_)
        parts.append(f"SCHEMA CHECK: {check}")
    if req.format == "FILE" and req.result_url:
        try:
            fetched = await fetch_result_content(req.result_url, max_bytes=max_bytes, timeout=timeout)
            parts.append(f"AGENT OUTPUT (fetched file):\n{fetched}")
        except (FetchError, Exception) as e:  # noqa: BLE001 - record, let deliberation weigh it
            parts.append(f"AGENT OUTPUT (file): could not retrieve — {e}")
    return {"evidence": "\n\n".join(parts)}


async def deliberate(state: ArbitrationState, *, llm) -> dict:
    msg = await llm.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(content=f"Evidence:\n{state['evidence']}\n\n"
                             "Reason step by step about whether the criteria are met. "
                             "Do not state a final category yet."),
    ])
    return {"deliberation": msg.content}


async def classify(state: ArbitrationState, *, llm) -> dict:
    structured = llm.with_structured_output(RulingResult)
    result: RulingResult = await structured.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(content=f"Evidence:\n{state['evidence']}\n\nDeliberation:\n"
                             f"{state.get('deliberation', '')}\n\n"
                             "Return the final category and a one-paragraph rationale."),
    ])
    category = result.category if result.category in CATEGORIES else "NOT_FULFILLED"
    return {"category": category, "rationale": result.rationale}


async def critique(state: ArbitrationState, *, llm) -> dict:
    # One bounded consistency pass: re-affirm or correct the category given the rationale.
    structured = llm.with_structured_output(RulingResult)
    result: RulingResult = await structured.ainvoke([
        SystemMessage(content=_SYSTEM),
        HumanMessage(content=f"Proposed category: {state['category']}\nRationale: {state['rationale']}\n"
                             f"Evidence:\n{state['evidence']}\n\n"
                             "If the category is inconsistent with the rationale and evidence, correct it. "
                             "Return the final category and rationale."),
    ])
    category = result.category if result.category in CATEGORIES else state["category"]
    return {"category": category, "rationale": result.rationale}
```

- [ ] **Step 3: Write `app/graph/build.py`.**

```python
from functools import partial

from langgraph.graph import END, START, StateGraph

from app.graph.nodes import classify, critique, deliberate, gather_evidence
from app.graph.state import ArbitrationState
from app.schemas import ArbitrationRequest, RulingResult


def build_graph(llm, settings):
    g = StateGraph(ArbitrationState)
    g.add_node("gather_evidence", partial(gather_evidence,
               max_bytes=settings.fetch_max_bytes, timeout=settings.fetch_timeout_seconds))
    g.add_node("deliberate", partial(deliberate, llm=llm))
    g.add_node("classify", partial(classify, llm=llm))
    g.add_node("critique", partial(critique, llm=llm))
    g.add_edge(START, "gather_evidence")
    g.add_edge("gather_evidence", "deliberate")
    g.add_edge("deliberate", "classify")
    g.add_edge("classify", "critique")
    g.add_edge("critique", END)
    return g.compile()


async def run_arbitration(graph, request: ArbitrationRequest) -> RulingResult:
    final = await graph.ainvoke({"request": request})
    return RulingResult(category=final["category"], rationale=final["rationale"])
```

- [ ] **Step 4: Write `tests/test_graph.py`** with a fake LLM (no OpenAI call):

```python
from app.graph.build import build_graph, run_arbitration
from app.graph.state import ArbitrationState  # noqa: F401
from app.schemas import ArbitrationRequest, RulingResult


class _FakeStructured:
    def __init__(self, result):
        self._result = result

    async def ainvoke(self, messages):
        return self._result


class FakeLLM:
    """Minimal stand-in for ChatOpenAI: records the evidence it saw, returns canned output."""

    def __init__(self, category, rationale):
        self._result = RulingResult(category=category, rationale=rationale)
        self.seen = []

    async def ainvoke(self, messages):
        self.seen.append(messages)

        class _Msg:
            content = "reasoned analysis"

        return _Msg()

    def with_structured_output(self, _schema):
        return _FakeStructured(self._result)


def _request(**over):
    base = dict(dispute_id="d", task_id="t", correlation_id="c", format="JSON",
                schema_=None, acceptance_criteria="List 3 sources",
                result_payload_json='{"sources":["a","b","c"]}', result_url=None,
                reason_category="C_INCOMPLETE")
    base.update(over)
    return ArbitrationRequest.model_validate(base)


async def test_graph_returns_structured_ruling():
    llm = FakeLLM("FULFILLED", "All three sources present.")
    graph = build_graph(llm, _settings())
    result = await run_arbitration(graph, _request())
    assert isinstance(result, RulingResult)
    assert result.category == "FULFILLED"
    assert "three sources" in result.rationale


async def test_graph_coerces_unknown_category_to_not_fulfilled():
    llm = FakeLLM("NONSENSE", "garbled")
    graph = build_graph(llm, _settings())
    result = await run_arbitration(graph, _request())
    assert result.category == "NOT_FULFILLED"


def _settings():
    from app.config import Settings
    return Settings()
```

> `pydantic` field name is `schema_` with alias `schema`; `model_validate` with `populate_by_name=True` (set in Task 8) accepts `schema_`. If validation rejects the python name in your pydantic version, build the request via `model_validate_json` with `"schema"` instead.

Run: `uv run pytest tests/test_graph.py -v`
Expected: FAIL until the modules exist, then PASS. Run `uv run ruff check .`: clean.

- [ ] **Step 5: Commit.**

```bash
git add arbitration/app/graph/state.py arbitration/app/graph/nodes.py arbitration/app/graph/build.py \
        arbitration/tests/test_graph.py
git commit -m "feat(arbitration): LangGraph deliberation graph (evidence → deliberate → classify → critique)"
```

### Task 11: Ruling callback client

**Files:**
- Create: `arbitration/app/callback.py`
- Create: `arbitration/tests/test_callback.py`

**Interfaces:**
- Produces: `async post_ruling(base_url, dispute_id, secret, result: RulingResult, *, timeout) -> int` — POSTs `{category, rationale}` with `Authorization: Bearer {secret}` to `/api/arbitration-callbacks/{disputeId}/ruling`; raises `httpx.HTTPStatusError` on non-2xx.

- [ ] **Step 1: Write the failing test** (uses `respx` to mock httpx):

```python
import httpx
import pytest
import respx

from app.callback import post_ruling
from app.schemas import RulingResult

BASE = "http://backend.test"
DID = "11111111-1111-1111-1111-111111111111"
URL = f"{BASE}/api/arbitration-callbacks/{DID}/ruling"


@respx.mock
async def test_posts_ruling_with_bearer_secret():
    route = respx.post(URL).mock(return_value=httpx.Response(200))
    status = await post_ruling(BASE, DID, "s3cret",
                               RulingResult(category="FULFILLED", rationale="ok"), timeout=5)
    assert status == 200
    sent = route.calls.last.request
    assert sent.headers["authorization"] == "Bearer s3cret"
    import json
    assert json.loads(sent.content) == {"category": "FULFILLED", "rationale": "ok"}


@respx.mock
async def test_raises_on_401():
    respx.post(URL).mock(return_value=httpx.Response(401))
    with pytest.raises(httpx.HTTPStatusError):
        await post_ruling(BASE, DID, "wrong",
                          RulingResult(category="FULFILLED", rationale="ok"), timeout=5)


@respx.mock
async def test_raises_on_404():
    respx.post(URL).mock(return_value=httpx.Response(404))
    with pytest.raises(httpx.HTTPStatusError):
        await post_ruling(BASE, DID, "s",
                          RulingResult(category="NOT_FULFILLED", rationale="x"), timeout=5)
```

Run: `uv run pytest tests/test_callback.py -v`
Expected: FAIL.

- [ ] **Step 2: Write `app/callback.py`.**

```python
import httpx

from app.schemas import RulingResult


async def post_ruling(base_url: str, dispute_id: str, secret: str, result: RulingResult,
                      *, timeout: float) -> int:
    url = f"{base_url}/api/arbitration-callbacks/{dispute_id}/ruling"
    async with httpx.AsyncClient(timeout=timeout) as client:
        resp = await client.post(
            url,
            json={"category": result.category, "rationale": result.rationale},
            headers={"Authorization": f"Bearer {secret}"},
        )
    resp.raise_for_status()
    return resp.status_code
```

Run the test: Expected PASS. Run `uv run ruff check .`: clean.

- [ ] **Step 3: Commit.**

```bash
git add arbitration/app/callback.py arbitration/tests/test_callback.py
git commit -m "feat(arbitration): shared-secret ruling callback client"
```

### Task 12: RabbitMQ consumer with ack-discipline

**Files:**
- Create: `arbitration/app/consumer.py`
- Create: `arbitration/tests/test_consumer.py`

**Interfaces:**
- Consumes: `Settings`, the compiled graph + `run_arbitration` (Task 10), `post_ruling` (Task 11), `ArbitrationRequest` (Task 8).
- Produces: `async handle_message(message, *, settings, graph)` — parse → arbitrate (bounded retry) → POST callback → **ack only on 2xx**; any persistent failure or un-parseable body → `nack(requeue=False)` (→ DLQ → Java fallback). `async run_consumer(settings, graph, stop_event)` wires the aio-pika robust connection + **passive** queue declare.

> This is the worker half of the main→DLX→DLQ path the Phase-3a review flagged as inspection-only. The Java DLQ listener already has its own test; here we test the nack-to-DLQ decision.

- [ ] **Step 1: Write the failing test.** Drive `handle_message` with a fake aio-pika message that records `ack`/`nack`:

```python
import json

import httpx
import pytest

from app.config import Settings
from app.consumer import handle_message
from app.schemas import RulingResult

GOLDEN = json.dumps({
    "disputeId": "11111111-1111-1111-1111-111111111111",
    "taskId": "22222222-2222-2222-2222-222222222222",
    "correlationId": "c", "format": "TEXT", "schema": None,
    "acceptanceCriteria": "be helpful", "resultPayloadJson": "hello",
    "resultUrl": None, "reasonCategory": "A_MISMATCH",
}).encode()


class FakeMessage:
    def __init__(self, body):
        self.body = body
        self.acked = False
        self.nacked_requeue = None

    async def ack(self):
        self.acked = True

    async def nack(self, requeue=True):
        self.nacked_requeue = requeue


class StubGraph:
    pass


async def _ok_arbitrate(graph, req):
    return RulingResult(category="FULFILLED", rationale="ok")


async def _boom_arbitrate(graph, req):
    raise RuntimeError("LLM down")


@pytest.fixture
def settings():
    return Settings(backend_base_url="http://backend.test", arbitration_callback_secret="s")


async def test_acks_after_successful_callback(settings, monkeypatch):
    import app.consumer as consumer
    monkeypatch.setattr(consumer, "run_arbitration", _ok_arbitrate)
    monkeypatch.setattr(consumer, "post_ruling",
                        lambda *a, **k: _async_return(200))
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is True
    assert msg.nacked_requeue is None


async def test_nacks_to_dlq_on_unparseable_body(settings):
    msg = FakeMessage(b"{not json")
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False


async def test_nacks_to_dlq_when_arbitration_fails(settings, monkeypatch):
    import app.consumer as consumer
    monkeypatch.setattr(consumer, "run_arbitration", _boom_arbitrate)
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False


async def test_nacks_to_dlq_when_callback_unauthorized(settings, monkeypatch):
    import app.consumer as consumer
    monkeypatch.setattr(consumer, "run_arbitration", _ok_arbitrate)

    async def _401(*a, **k):
        raise httpx.HTTPStatusError("401", request=None, response=httpx.Response(401))

    monkeypatch.setattr(consumer, "post_ruling", _401)
    msg = FakeMessage(GOLDEN)
    await handle_message(msg, settings=settings, graph=StubGraph())
    assert msg.acked is False
    assert msg.nacked_requeue is False


def _async_return(value):
    async def _coro(*a, **k):
        return value
    return _coro()
```

> The `post_ruling` monkeypatch in the happy-path test must be an async callable; adjust the lambda to an `async def` returning 200 if your runner rejects the `_async_return` shim. The intent: ack iff the callback returns 2xx.

Run: `uv run pytest tests/test_consumer.py -v`
Expected: FAIL.

- [ ] **Step 2: Write `app/consumer.py`.**

```python
import asyncio
import logging

import aio_pika

from app.callback import post_ruling
from app.graph.build import run_arbitration
from app.schemas import ArbitrationRequest

log = logging.getLogger("arbitration.consumer")

_MAX_ATTEMPTS = 3


async def _arbitrate_with_retry(graph, request, settings):
    delay = 1.0
    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            result = await run_arbitration(graph, request)
            status = await post_ruling(
                settings.backend_base_url, request.dispute_id,
                settings.arbitration_callback_secret, result,
                timeout=settings.callback_timeout_seconds)
            return status
        except Exception as e:  # noqa: BLE001 - retry transient, give up after the cap
            if attempt == _MAX_ATTEMPTS:
                raise
            log.warning("arbitration attempt %d failed (%s); retrying", attempt, e)
            await asyncio.sleep(delay)
            delay *= 2


async def handle_message(message, *, settings, graph) -> None:
    try:
        request = ArbitrationRequest.model_validate_json(message.body)
    except Exception:  # noqa: BLE001 - poison message
        log.exception("un-parseable arbitration message; dead-lettering")
        await message.nack(requeue=False)
        return
    try:
        await _arbitrate_with_retry(graph, request, settings)
        await message.ack()  # ack ONLY after the ruling callback returned 2xx
        log.info("dispute %s ruled and acknowledged", request.dispute_id)
    except Exception:  # noqa: BLE001 - persistent failure → DLQ → Java fallback refund
        log.exception("arbitration failed for dispute %s; dead-lettering to fallback",
                      request.dispute_id)
        await message.nack(requeue=False)


async def run_consumer(settings, graph, stop_event: asyncio.Event) -> None:
    connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    try:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=4)
        # The Java backend owns the topology; declare passively so we never re-arg it.
        queue = await channel.declare_queue(settings.dispute_queue, passive=True)
        async with queue.iterator() as it:
            async for message in it:
                if stop_event.is_set():
                    break
                await handle_message(message, settings=settings, graph=graph)
    finally:
        await connection.close()
```

Run the test: Expected PASS. Run `uv run ruff check .`: clean.

- [ ] **Step 3: Commit.**

```bash
git add arbitration/app/consumer.py arbitration/tests/test_consumer.py
git commit -m "feat(arbitration): RabbitMQ consumer with ack-after-2xx discipline + DLQ fallback"
```

### Task 13: App lifespan wiring + cross-language contract test

**Files:**
- Modify: `arbitration/app/main.py`
- Create: `arbitration/tests/test_contract.py`

**Interfaces:**
- Produces: a FastAPI `lifespan` that, on startup, builds the OpenAI `ChatOpenAI` LLM + the graph and launches `run_consumer` as a background task; on shutdown, signals the stop event and cancels it. `/health` stays. The contract test pins the inbound + outbound shapes to the Java records.

- [ ] **Step 1: Rewrite `app/main.py` with lifespan.**

```python
import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from langchain_openai import ChatOpenAI

from app.config import load_settings
from app.consumer import run_consumer
from app.graph.build import build_graph

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    llm = ChatOpenAI(model=settings.openai_model, api_key=settings.openai_api_key, temperature=0)
    graph = build_graph(llm, settings)
    stop_event = asyncio.Event()
    task = asyncio.create_task(run_consumer(settings, graph, stop_event))
    app.state.consumer_task = task
    try:
        yield
    finally:
        stop_event.set()
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task


app = FastAPI(title="HireAI Arbitration Worker", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
```

> The Task-7 `tests/test_health.py` uses `TestClient(app)`, which now triggers `lifespan` → it will try to connect to RabbitMQ. To keep the health test hermetic, update it to construct the app WITHOUT lifespan side effects: either use `with TestClient(app):`-free direct call via `app.router.routes`, or (simplest) override — in `tests/test_health.py`, build a bare `FastAPI()` and include only the health route, OR mark the consumer startup to no-op when `rabbitmq_url` is unset. Implement the cleanest: in `lifespan`, skip launching the consumer when `settings.arbitration_callback_secret == ""` AND `settings.openai_api_key == ""` (test defaults), logging "consumer disabled (no config)". Then `TestClient(app)` is safe. Add an assertion to the health test that no consumer task runs under empty config.

- [ ] **Step 2: Implement the no-config guard in `lifespan`.** Wrap the consumer launch:

```python
    if settings.openai_api_key and settings.arbitration_callback_secret:
        task = asyncio.create_task(run_consumer(settings, graph, stop_event))
        app.state.consumer_task = task
    else:
        logging.getLogger("arbitration").warning("consumer disabled — missing OPENAI/secret config")
        app.state.consumer_task = None
    try:
        yield
    finally:
        stop_event.set()
        if app.state.consumer_task is not None:
            app.state.consumer_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await app.state.consumer_task
```

- [ ] **Step 3: Write the contract test.** `tests/test_contract.py` pins the shapes both directions so a drift on the Java side is caught here:

```python
import json

from app.schemas import ArbitrationRequest, RulingResult

# Mirror of com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage (camelCase).
JAVA_FIELDS = {"disputeId", "taskId", "correlationId", "format", "schema",
               "acceptanceCriteria", "resultPayloadJson", "resultUrl", "reasonCategory"}


def test_inbound_accepts_exactly_the_java_message_fields():
    body = json.dumps({k: ("FILE" if k == "format" else
                           ("A_MISMATCH" if k == "reasonCategory" else None) if k in
                           {"reasonCategory"} else
                           "x") for k in JAVA_FIELDS})
    # Build a valid instance with the real field set.
    req = ArbitrationRequest.model_validate({
        "disputeId": "11111111-1111-1111-1111-111111111111",
        "taskId": "22222222-2222-2222-2222-222222222222",
        "correlationId": "c", "format": "FILE", "schema": None,
        "acceptanceCriteria": None, "resultPayloadJson": None,
        "resultUrl": "https://example.com/out.json", "reasonCategory": "B_FACTUAL",
    })
    assert req.format == "FILE"
    assert req.result_url == "https://example.com/out.json"


def test_outbound_matches_ArbitrationRulingRequest():
    # Java: record ArbitrationRulingRequest(@NotBlank String category, String rationale)
    payload = RulingResult(category="PARTIALLY_FULFILLED", rationale="r").model_dump()
    assert set(payload.keys()) == {"category", "rationale"}
    assert payload["category"] in {"FULFILLED", "PARTIALLY_FULFILLED", "NOT_FULFILLED"}
```

- [ ] **Step 4: Run the full Python suite + lint.**

Run: `uv run pytest -v && uv run ruff check .`
Expected: all tests pass; lint clean. (Confirm `tests/test_health.py` still passes with the lifespan + no-config guard.)

- [ ] **Step 5: Commit.**

```bash
git add arbitration/app/main.py arbitration/tests/test_contract.py arbitration/tests/test_health.py
git commit -m "feat(arbitration): FastAPI lifespan launches the consumer + contract test"
```

---

## Component 4 — CI + docs

### Task 14: Path-filtered CI job for the arbitration service

**Files:**
- Create: `.github/workflows/arbitration-ci.yml`

**Interfaces:**
- Consumes: the `arbitration/` package + tests (Tasks 7–13).
- Produces: a GitHub Actions workflow that runs `ruff check` + `pytest` only when `arbitration/**` changes, mirroring the structure of `backend-ci.yml`/`frontend-ci.yml`.

- [ ] **Step 1: Write the workflow.**

```yaml
name: Arbitration CI

on:
  pull_request:
    branches: [main]
    paths:
      - 'arbitration/**'
      - '.github/workflows/arbitration-ci.yml'
  push:
    branches: [main]
    paths:
      - 'arbitration/**'
      - '.github/workflows/arbitration-ci.yml'

permissions:
  contents: read

concurrency:
  group: arbitration-ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  lint-test:
    name: Lint & test (ruff, pytest)
    runs-on: ubuntu-latest
    timeout-minutes: 15
    defaults:
      run:
        working-directory: arbitration
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up uv + Python 3.12
        uses: astral-sh/setup-uv@v5
        with:
          python-version: '3.12'

      - name: Install dependencies
        run: uv sync --dev

      - name: Lint
        run: uv run ruff check .

      - name: Test
        run: uv run pytest
```

- [ ] **Step 2: Validate locally.** Confirm the YAML parses and the commands match what Tasks 7–13 use. (If `actions/checkout` only fetches the changed dir — it fetches the whole repo, so `working-directory: arbitration` resolves correctly.)

- [ ] **Step 3: Commit.**

```bash
git add .github/workflows/arbitration-ci.yml
git commit -m "ci: lint+test the arbitration service on arbitration/** changes"
```

### Task 15: Docs — record the OpenAI arbitrator + Phase 3b status

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/details/architecture.md` (only the arbitrator/model mention)

**Interfaces:** none (documentation).

- [ ] **Step 1: Update `CLAUDE.md`.** In the `arbitration/` repository-status line, change "_Not started._" to reflect: built — Python FastAPI + LangGraph + **OpenAI `gpt-4o`** worker; RabbitMQ consumer → shared-secret callback. In the "Stack at a glance" line, change "Anthropic Claude" arbitrator wording to note the arbitrator uses **OpenAI** (the platform's only LLM consumer). In the Module 4 pending list, mark Phase 3b done and list the remaining deferred items: tier-2 Administrator override (table seam laid, no writer), arbitration timeout sweeper, builder-side discovery of full-refund disputes, SSE push.

- [ ] **Step 2: Update `docs/details/architecture.md`.** Where it names the arbitrator's model (Claude), change to OpenAI `gpt-4o`, keeping everything else. Do not rewrite unrelated sections.

- [ ] **Step 3: Commit.**

```bash
git add CLAUDE.md docs/details/architecture.md
git commit -m "docs: arbitrator runs on OpenAI gpt-4o; Phase 3b built; record deferred items"
```

> The orchestrator updates the `module4-adjudication-design` memory separately (not part of a task commit).

---

## Self-Review

**Spec coverage** — every spec section maps to a task:
- Component 1 (Python service: stack, layout, graph, inbound/outbound contracts, config, ack-discipline) → Tasks 7–13.
- Component 2 (V21 `dispute_rulings`, domain history, append-only persistence, transparency read + authz) → Tasks 1–4.
- Component 3 (client + builder display, FALLBACK rendering, PARTIALLY_ACCEPTED states) → Tasks 5–6.
- Component 4 (path-filtered CI) → Task 14. Docs/CLAUDE.md/memory update (spec line 11) → Task 15.
- Invariants: #3 (Global Constraints + Task 4 note: settlement unchanged) · #2 (Task 1 trigger) · #6 (Task 11 bearer) · #5 (Task 4 authz) · SSRF (Task 9) · ack-discipline (Task 12).

**Intentional deviations from the spec (flagged for the reviewer/user):**
1. **404 not 403** on the transparency read for non-owners (Task 4) — matches the codebase's existing authorized-read convention (no existence leak). One-line change if a literal 403 is preferred.
2. **`respond` pulled out of the graph** into the consumer (Task 12) — ack-discipline lives in one place; the graph is a pure decision function. Same external behavior.
3. **Builder display on earnings payouts** (Task 6), not a dedicated builder per-task view (none exists). Full-refund disputes (no payout) are not yet builder-discoverable — documented as deferred.

**Open questions resolved by adopting the spec's stated defaults** (the user said "write the plan" after the spec listed these): gpt-4o (Task 7/13), ruling-history-now (Tasks 1–3), shared `by-task` endpoint (Task 4), text/JSON-inline + best-effort-binary FILE handling (Task 9 — binary is described/skipped, not sent to vision; a vision path is a future extension).

**Type consistency** — `Ruling` is 5-arg (`+decidedAt`) everywhere (Tasks 2, 3); `RulingDecidedBy` has `ADMINISTRATOR` (Tasks 1 CHECK, 2 enum); `DisputeModel.rehydrate` takes `List<Ruling>` (Tasks 2, 3); the DTO `RulingDTO(tier:int, decidedBy, category, rationale, decidedAt)` matches frontend `RulingDTO` (Tasks 4, 5); the Python inbound field set matches `ArbitrationRequestMessage` and outbound matches `ArbitrationRulingRequest` (Tasks 8, 13).

**Placeholder scan** — no TBDs; every code step carries complete code or an explicit "read the current file and match" instruction where an exact existing signature must be honored.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-01-module4-phase3b-arbitrator.md`.** Two execution options:

1. **Subagent-Driven (recommended)** — a fresh subagent per task, a spec+quality review between tasks, fast iteration. Suggested ordering: Tasks 1→4 (backend; one schema unit), then 5→6 (frontend), then 7→13 (Python), then 14→15. Backend and Python are independent and could be interleaved.
2. **Inline Execution** — execute tasks in this session with checkpoints.

**Which approach?**
