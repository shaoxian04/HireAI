# Agent Storefront & Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clients can discover, inspect, and directly book agents from a marketplace; builders manage an e-commerce-style storefront (content, images, pricing, visibility) and see real per-agent statistics — delivering Module 6 + the display half of Module 5.

**Architecture:** New `agent_profiles` + `reviews` tables (Flyway V6/V7) feed a read-only `/api/catalogue/*` surface (JdbcTemplate read DAO over agents/profiles/reviews/tasks). Direct booking extends the existing submit→event→route pipeline with a pre-chosen `agentVersionId` that skips the matcher but reuses assign+RabbitMQ+signed-webhook dispatch. Builder management extends `/api/agents/*` with owner-checked profile/media/pricing/stats endpoints; images go to Supabase Storage via a backend-only `MediaStoragePort`. Frontend adds Marketplace, Storefront, Booking, and Manage surfaces in the existing Mission Control design system.

**Tech Stack:** Spring Boot 3 (Java 21, DDD layering, Flyway, JPA, RabbitMQ, RestClient), Supabase Postgres + Storage, Next.js 16 + React 19 + Tailwind 4, Vitest/RTL/MSW, JUnit/MockMvc/Testcontainers.

**Spec:** `docs/superpowers/specs/2026-06-06-agent-storefront-discovery-design.md` (approved). Branch: `feat/marketplace-spine`.

**Conventions that bind every task** (from the live codebase — read these files before coding):
- `docs/details/ddd-conventions.md` — layering; app services = interface + `impl/` (`@Service` on impl); domain models framework-free + immutable (transitions return new copies); `DomainException(ResultCode.X, msg)` for failures; one repository per aggregate root, JPA impl in `infrastructure/repository/<context>/`.
- Controllers extend `BaseController`, return `WebResult<T>` via `ok(...)`; identity ONLY from `CurrentUserProvider.currentUserId()` (Invariant #5); ownership = load-scoped-to-owner, missing/foreign → `NOT_FOUND` (no existence leak).
- HTTP mapping (GlobalExceptionConfiguration): NOT_FOUND→404, INSUFFICIENT_BALANCE/DOMAIN_RULE_VIOLATION→409, VALIDATION_ERROR→400.
- Tests: controller slices = `@WebMvcTest(X.class) @Import(SecurityConfig.class) @WithMockUser @ActiveProfiles("test")` with `@MockBean` services; integration = `@SpringBootTest @Testcontainers @ActiveProfiles("test") @EnabledIf("dockerAvailable")` + `@DynamicPropertySource` (copy the docker-skip helper from `WalletLedgerIntegrationTest`).
- Frontend: ONLY `lib/api.ts` calls fetch; pages = `"use client"` + `AppShell` + `RoleGuard`; reuse `components/ui/*`, design tokens from `app/globals.css` (dark Mission Control — NO light/slate styles); tests seed `hireai.token` + `hireai.auth` localStorage and wrap in `<AuthProvider>`, MSW handlers in `test/msw/handlers.ts`.
- Commits: conventional format (`feat:`/`fix:`/`test:`/`docs:`/`chore:`), **no attribution footer** (disabled in user settings). Commands from repo root unless stated.

---

## File structure (created ▸ / modified △)

**Backend — schema & domain**
- ▸ `backend/src/main/resources/db/migration/V6__agent_profiles.sql` — storefront table + backfill
- ▸ `backend/src/main/resources/db/migration/V7__reviews.sql` — reviews table + demo seed
- ▸ `backend/src/main/java/com/hireai/domain/biz/agent/model/AgentProfileModel.java` — storefront content (immutable, gallery cap)
- ▸ `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentProfileRepository.java`
- ▸ `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentProfileJpaEntity.java` / `AgentProfileJpaRepository.java` / `AgentProfileRepositoryImpl.java`
- ▸ `backend/src/main/java/com/hireai/domain/biz/review/model/ReviewModel.java` + `repository/ReviewRepository.java`
- ▸ `backend/src/main/java/com/hireai/infrastructure/repository/review/ReviewJpaEntity.java` / `ReviewJpaRepository.java` / `ReviewRepositoryImpl.java`
- △ `backend/src/main/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImpl.java` — create default profile on register; pricing update
- △ `backend/src/main/java/com/hireai/domain/biz/agent/model/AgentVersionModel.java` — `updateCommercials(...)`
- △ `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java` + `AgentRepositoryImpl` — `updateCurrentVersion`, `findCandidateByVersionId`

**Backend — storage port**
- ▸ `backend/src/main/java/com/hireai/application/port/storage/MediaStoragePort.java`
- ▸ `backend/src/main/java/com/hireai/infrastructure/client/SupabaseStorageClient.java`
- △ `backend/src/main/resources/application.yml` — `hireai.storage.*`, multipart limits

**Backend — catalogue read surface**
- ▸ `backend/src/main/java/com/hireai/application/port/query/CatalogueQueryPort.java` — row records + query contract
- ▸ `backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcCatalogueQueryDao.java`
- ▸ `backend/src/main/java/com/hireai/application/biz/catalogue/CatalogueReadAppService.java` + `impl/CatalogueReadAppServiceImpl.java`
- ▸ `backend/src/main/java/com/hireai/controller/biz/catalogue/CatalogueController.java` + `dto/AgentCardDTO.java` / `dto/AgentProfileDTO.java` / `dto/CategoryCountDTO.java`

**Backend — direct booking**
- △ `backend/src/main/java/com/hireai/domain/biz/task/event/TaskSubmittedDomainEvent.java` — add nullable `directAgentVersionId`
- △ `backend/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java` + impl — `submitDirectlyBooked(...)`
- △ `backend/src/main/java/com/hireai/application/biz/routing/RoutingAppService.java` + impl — `dispatchDirect(...)`; extract `buildDispatchMessage`
- △ `backend/src/main/java/com/hireai/application/biz/routing/RoutingEventListener.java` — branch on direct target
- ▸ `backend/src/main/java/com/hireai/application/biz/task/DirectBookingAppService.java` + `impl/DirectBookingAppServiceImpl.java`
- △ `backend/src/main/java/com/hireai/controller/biz/task/dto/SubmitTaskRequest.java` + `TaskController.java` — optional `agentId`, optional `outputSpec`/`category` when direct

**Backend — builder management & stats**
- △ `backend/src/main/java/com/hireai/controller/biz/agent/AgentController.java` — profile/media/pricing/stats/reviews endpoints
- ▸ `backend/src/main/java/com/hireai/controller/biz/agent/dto/AgentProfileViewDTO.java` / `UpdateProfileRequest.java` / `UpdatePricingRequest.java` / `AgentStatsDTO.java` / `ReviewDTO.java` / `RespondReviewRequest.java`
- ▸ `backend/src/main/java/com/hireai/application/biz/agent/AgentStorefrontAppService.java` + `impl/AgentStorefrontAppServiceImpl.java` — profile/media/reviews-response orchestration

**Frontend**
- △ `frontend/lib/types.ts` — catalogue/stats/review/profile DTOs; `CreateTaskRequest.agentId?`
- △ `frontend/lib/api.ts` — extract envelope parsing; add `apiUpload` (multipart, no JSON header)
- ▸ `frontend/components/AgentCard.tsx`, `CategoryBar.tsx`, `RatingStars.tsx`, `StatTile.tsx`, `Sparkline.tsx`, `MediaUploader.tsx`, `ReviewList.tsx`
- △ `frontend/app/client/page.tsx` — becomes the Marketplace
- ▸ `frontend/app/client/tasks/page.tsx` — old client console (wallet + task list) moves here
- ▸ `frontend/app/client/agents/[id]/page.tsx` — storefront; ▸ `frontend/app/client/agents/[id]/book/page.tsx` — direct booking
- △ `frontend/app/builder/page.tsx` — quick stats + Manage link; ▸ `frontend/app/builder/agents/[id]/page.tsx` — manage tabs (Storefront / Pricing / Stats / Reviews)
- △ `frontend/components/Nav.tsx` — console nav links per role
- △ `frontend/test/msw/handlers.ts`; ▸ `frontend/test/marketplace.test.tsx`, `storefront.test.tsx`, `booking.test.tsx`, `manage.test.tsx`, `clientTasks.test.tsx`

**Docs**
- △ `docs/details/data-model.md`, `docs/details/frontend.md`, `CLAUDE.md` (one-line status), `docs/details/demo-runbook.md` (bucket setup)

---

# Phase 1 — Schema, domain, storage foundation

### Task 1: Flyway V6 — `agent_profiles`

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__agent_profiles.sql`
- Test: `backend/src/test/java/com/hireai/agent/AgentProfileRepositoryIntegrationTest.java` (created in Task 2; this task verifies via Flyway on Testcontainers)

- [ ] **Step 1: Write the migration**

```sql
-- V6: Agent storefront profiles (Module 6 — Discovery). 1:1 with agents; marketing/storefront
-- content lives here, NOT on the core agent aggregate. An agent appears in the public catalogue
-- only when agents.status = 'ACTIVE' AND agent_profiles.is_listed.
CREATE TABLE agent_profiles (
    agent_id      UUID PRIMARY KEY REFERENCES agents (id),
    tagline       TEXT,
    description   TEXT,
    sample_output TEXT,
    logo_url      TEXT,
    cover_url     TEXT,
    gallery_urls  TEXT[] NOT NULL DEFAULT '{}',
    is_listed     BOOLEAN NOT NULL DEFAULT FALSE,
    is_featured   BOOLEAN NOT NULL DEFAULT FALSE,
    gmt_create    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Backfill: every existing agent gets a profile row. Already-ACTIVE agents are listed so the
-- marketplace is not empty on first boot after this migration (demo continuity).
INSERT INTO agent_profiles (agent_id, is_listed)
SELECT id, (status = 'ACTIVE') FROM agents;
```

- [ ] **Step 2: Verify the backend still boots its schema validation**

Run: `mvn -f backend/pom.xml -B test -Dtest=HireAiApplicationTests`
Expected: PASS (context loads; Flyway/Hibernate `validate` see no drift — profiles table has no entity yet, which is fine: validate only checks mapped entities).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V6__agent_profiles.sql
git commit -m "feat: add agent_profiles storefront table (V6)"
```

### Task 2: AgentProfile domain model + repository (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/model/AgentProfileModel.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentProfileRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentProfileJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentProfileJpaRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentProfileRepositoryImpl.java`
- Test: `backend/src/test/java/com/hireai/agent/AgentProfileModelTest.java`
- Test: `backend/src/test/java/com/hireai/agent/AgentProfileRepositoryIntegrationTest.java`

- [ ] **Step 1: Write the failing domain-model unit test**

```java
package com.hireai.agent;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProfileModelTest {

    @Test
    void createDefaultIsUnlistedAndEmpty() {
        UUID agentId = UUID.randomUUID();
        AgentProfileModel p = AgentProfileModel.createDefault(agentId);
        assertThat(p.agentId()).isEqualTo(agentId);
        assertThat(p.listed()).isFalse();
        assertThat(p.galleryUrls()).isEmpty();
    }

    @Test
    void updateContentTrimsAndSetsListing() {
        AgentProfileModel p = AgentProfileModel.createDefault(UUID.randomUUID())
                .updateContent(" Fast summaries ", "Does X", "{\"sample\":1}", true);
        assertThat(p.tagline()).isEqualTo("Fast summaries");
        assertThat(p.listed()).isTrue();
    }

    @Test
    void taglineOverLimitRejected() {
        AgentProfileModel p = AgentProfileModel.createDefault(UUID.randomUUID());
        assertThatThrownBy(() -> p.updateContent("x".repeat(161), null, null, false))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode())
                .isEqualTo(ResultCode.VALIDATION_ERROR);
    }

    @Test
    void galleryCapEnforced() {
        AgentProfileModel p = AgentProfileModel.createDefault(UUID.randomUUID());
        for (int i = 0; i < AgentProfileModel.MAX_GALLERY; i++) {
            p = p.addGalleryUrl("https://cdn.example.com/" + i + ".png");
        }
        AgentProfileModel full = p;
        assertThatThrownBy(() -> full.addGalleryUrl("https://cdn.example.com/extra.png"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode())
                .isEqualTo(ResultCode.DOMAIN_RULE_VIOLATION);
    }

    @Test
    void removeGalleryUrlRemovesExactMatch() {
        AgentProfileModel p = AgentProfileModel.createDefault(UUID.randomUUID())
                .addGalleryUrl("https://cdn.example.com/a.png")
                .addGalleryUrl("https://cdn.example.com/b.png")
                .removeMedia("gallery", "https://cdn.example.com/a.png");
        assertThat(p.galleryUrls()).containsExactly("https://cdn.example.com/b.png");
    }
}
```

- [ ] **Step 2: Run it — must fail to compile (class missing)**

Run: `mvn -f backend/pom.xml -B test -Dtest=AgentProfileModelTest`
Expected: COMPILATION ERROR — `AgentProfileModel` does not exist.

- [ ] **Step 3: Implement the domain model**

```java
package com.hireai.domain.biz.agent.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storefront content for an Agent (1:1 with the aggregate root, table agent_profiles).
 * Marketing concern only — never touches the routable contract. Immutable: every change
 * returns a new copy. Catalogue visibility = agents.status ACTIVE AND listed here.
 */
public final class AgentProfileModel {

    public static final int MAX_GALLERY = 6;
    public static final int MAX_TAGLINE = 160;
    public static final int MAX_TEXT = 8000;

    private final UUID agentId;
    private final String tagline;
    private final String description;
    private final String sampleOutput;
    private final String logoUrl;
    private final String coverUrl;
    private final List<String> galleryUrls;
    private final boolean listed;
    private final boolean featured;

    public AgentProfileModel(UUID agentId, String tagline, String description, String sampleOutput,
                             String logoUrl, String coverUrl, List<String> galleryUrls,
                             boolean listed, boolean featured) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.galleryUrls = List.copyOf(galleryUrls);
        this.listed = listed;
        this.featured = featured;
    }

    /** Factory for registration: empty, unlisted, unfeatured. */
    public static AgentProfileModel createDefault(UUID agentId) {
        if (agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "agent id is required");
        }
        return new AgentProfileModel(agentId, null, null, null, null, null, List.of(), false, false);
    }

    /** Builder edits the storefront text + listing toggle. */
    public AgentProfileModel updateContent(String tagline, String description,
                                           String sampleOutput, boolean listed) {
        return new AgentProfileModel(agentId,
                limited(tagline, MAX_TAGLINE, "tagline"),
                limited(description, MAX_TEXT, "description"),
                limited(sampleOutput, MAX_TEXT, "sample output"),
                logoUrl, coverUrl, galleryUrls, listed, featured);
    }

    public AgentProfileModel withLogo(String url) {
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                url, coverUrl, galleryUrls, listed, featured);
    }

    public AgentProfileModel withCover(String url) {
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                logoUrl, url, galleryUrls, listed, featured);
    }

    public AgentProfileModel addGalleryUrl(String url) {
        if (galleryUrls.size() >= MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + MAX_GALLERY + " images)");
        }
        List<String> next = new ArrayList<>(galleryUrls);
        next.add(url);
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                logoUrl, coverUrl, next, listed, featured);
    }

    /** Removes a media entry; kind is logo|cover|gallery. Unknown URL is a no-op for gallery. */
    public AgentProfileModel removeMedia(String kind, String url) {
        return switch (kind) {
            case "logo" -> withLogo(null);
            case "cover" -> withCover(null);
            case "gallery" -> {
                List<String> next = new ArrayList<>(galleryUrls);
                next.remove(url);
                yield new AgentProfileModel(agentId, tagline, description, sampleOutput,
                        logoUrl, coverUrl, next, listed, featured);
            }
            default -> throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unknown media kind: " + kind);
        };
    }

    private static String limited(String value, int max, String field) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    field + " must be at most " + max + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID agentId() { return agentId; }
    public String tagline() { return tagline; }
    public String description() { return description; }
    public String sampleOutput() { return sampleOutput; }
    public String logoUrl() { return logoUrl; }
    public String coverUrl() { return coverUrl; }
    public List<String> galleryUrls() { return galleryUrls; }
    public boolean listed() { return listed; }
    public boolean featured() { return featured; }
}
```

- [ ] **Step 4: Run the unit test — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest=AgentProfileModelTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Write the failing repository integration test**

Copy the `dockerAvailable()` helper + container/datasource boilerplate from `backend/src/test/java/com/hireai/wallet/WalletLedgerIntegrationTest.java` verbatim.

```java
package com.hireai.agent;

import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AgentProfileRepositoryIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AgentProfileRepository repository;
    @Autowired JdbcTemplate jdbc;

    /** agents.owner_id has an FK to users; agent_profiles FKs agents. Seed both. */
    private UUID newAgent() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')",
                owner, owner + "@test.local");
        UUID agentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agents (id, owner_id, name, status, reputation_score)
                VALUES (?, ?, 'Test Agent', 'ACTIVE', 50.00)
                """, agentId, owner);
        return agentId;
    }

    @Test
    void savesAndRoundTripsProfileIncludingGalleryArray() {
        UUID agentId = newAgent();
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId)
                .updateContent("Tagline", "What it does", "{\"sample\":true}", true)
                .withLogo("https://cdn.example.com/logo.png")
                .addGalleryUrl("https://cdn.example.com/1.png")
                .addGalleryUrl("https://cdn.example.com/2.png");

        repository.save(profile);
        AgentProfileModel loaded = repository.findByAgentId(agentId).orElseThrow();

        assertThat(loaded.tagline()).isEqualTo("Tagline");
        assertThat(loaded.listed()).isTrue();
        assertThat(loaded.logoUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(loaded.galleryUrls())
                .isEqualTo(List.of("https://cdn.example.com/1.png", "https://cdn.example.com/2.png"));
    }

    @Test
    void saveUpsertsOnSecondWrite() {
        UUID agentId = newAgent();
        repository.save(AgentProfileModel.createDefault(agentId));
        repository.save(repository.findByAgentId(agentId).orElseThrow()
                .updateContent("Updated", null, null, true));

        assertThat(repository.findByAgentId(agentId).orElseThrow().tagline()).isEqualTo("Updated");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profiles WHERE agent_id = ?", Integer.class, agentId);
        assertThat(rows).isEqualTo(1);
    }
}
```

- [ ] **Step 6: Run it — must fail (interface missing)**

Run: `mvn -f backend/pom.xml -B test -Dtest=AgentProfileRepositoryIntegrationTest`
Expected: COMPILATION ERROR — `AgentProfileRepository` does not exist.

- [ ] **Step 7: Implement repository interface, JPA entity, JPA repo, impl**

`AgentProfileRepository.java` (domain — no framework imports):

```java
package com.hireai.domain.biz.agent.repository;

import com.hireai.domain.biz.agent.model.AgentProfileModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the storefront profile (1:1 with the Agent root). */
public interface AgentProfileRepository {

    AgentProfileModel save(AgentProfileModel profile);

    Optional<AgentProfileModel> findByAgentId(UUID agentId);
}
```

`AgentProfileJpaEntity.java` (mirror the TEXT[] mapping used by `AgentVersionJpaEntity`):

```java
package com.hireai.infrastructure.repository.agent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** JPA entity for agent_profiles. gallery_urls is a Postgres TEXT[] mapped to List<String>. */
@Entity
@Table(name = "agent_profiles")
public class AgentProfileJpaEntity {

    @Id
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "tagline")
    private String tagline;

    @Column(name = "description")
    private String description;

    @Column(name = "sample_output")
    private String sampleOutput;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "cover_url")
    private String coverUrl;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "gallery_urls", columnDefinition = "text[]", nullable = false)
    private List<String> galleryUrls;

    @Column(name = "is_listed", nullable = false)
    private boolean isListed;

    @Column(name = "is_featured", nullable = false)
    private boolean isFeatured;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    @Column(name = "gmt_modified", nullable = false)
    private Instant gmtModified;

    protected AgentProfileJpaEntity() {
    }

    public AgentProfileJpaEntity(UUID agentId, String tagline, String description, String sampleOutput,
                                 String logoUrl, String coverUrl, List<String> galleryUrls,
                                 boolean isListed, boolean isFeatured,
                                 Instant gmtCreate, Instant gmtModified) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.galleryUrls = galleryUrls;
        this.isListed = isListed;
        this.isFeatured = isFeatured;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
    }

    public UUID getAgentId() { return agentId; }
    public String getTagline() { return tagline; }
    public String getDescription() { return description; }
    public String getSampleOutput() { return sampleOutput; }
    public String getLogoUrl() { return logoUrl; }
    public String getCoverUrl() { return coverUrl; }
    public List<String> getGalleryUrls() { return galleryUrls; }
    public boolean isListed() { return isListed; }
    public boolean isFeatured() { return isFeatured; }
    public Instant getGmtCreate() { return gmtCreate; }
    public Instant getGmtModified() { return gmtModified; }
}
```

`AgentProfileJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.agent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AgentProfileJpaRepository extends JpaRepository<AgentProfileJpaEntity, UUID> {
}
```

`AgentProfileRepositoryImpl.java`:

```java
package com.hireai.infrastructure.repository.agent;

import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** JPA implementation. save() upserts by PK; gmt_create is preserved on update. */
@Repository
public class AgentProfileRepositoryImpl implements AgentProfileRepository {

    private final AgentProfileJpaRepository jpa;

    public AgentProfileRepositoryImpl(AgentProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AgentProfileModel save(AgentProfileModel profile) {
        Instant now = Instant.now();
        Instant created = jpa.findById(profile.agentId())
                .map(AgentProfileJpaEntity::getGmtCreate)
                .orElse(now);
        jpa.save(new AgentProfileJpaEntity(
                profile.agentId(), profile.tagline(), profile.description(), profile.sampleOutput(),
                profile.logoUrl(), profile.coverUrl(), profile.galleryUrls(),
                profile.listed(), profile.featured(), created, now));
        return profile;
    }

    @Override
    public Optional<AgentProfileModel> findByAgentId(UUID agentId) {
        return jpa.findById(agentId).map(e -> new AgentProfileModel(
                e.getAgentId(), e.getTagline(), e.getDescription(), e.getSampleOutput(),
                e.getLogoUrl(), e.getCoverUrl(), e.getGalleryUrls(),
                e.isListed(), e.isFeatured()));
    }
}
```

- [ ] **Step 8: Run both tests — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest="AgentProfileModelTest,AgentProfileRepositoryIntegrationTest"`
Expected: PASS (integration skips cleanly if Docker is down — then start Docker and re-run before continuing).

- [ ] **Step 9: Create the profile on registration**

Modify `backend/src/main/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImpl.java`:
inject `AgentProfileRepository agentProfileRepository` via the constructor (the class uses Lombok `@RequiredArgsConstructor` — add a `private final` field), and in `register(...)`, immediately after the existing `agentRepository.save(...)` line add:

```java
agentProfileRepository.save(AgentProfileModel.createDefault(agentId));
```

(`agentId` = the saved aggregate's id, matching the variable already returned by the method.) Also add a regression assertion to the existing registration integration test `backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java`:

```java
@Autowired AgentProfileRepository agentProfileRepository;
// ... inside the happy-path registration test, after register succeeds:
assertThat(agentProfileRepository.findByAgentId(agentId)).isPresent();
```

- [ ] **Step 10: Run the agent test suite — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest="Agent*"`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/agent/model/AgentProfileModel.java backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentProfileRepository.java backend/src/main/java/com/hireai/infrastructure/repository/agent/AgentProfile*.java backend/src/main/java/com/hireai/application/biz/agent/impl/AgentWriteAppServiceImpl.java backend/src/test/java/com/hireai/agent/AgentProfile*.java backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java
git commit -m "feat: agent storefront profile model + repository, created on registration"
```

### Task 3: Flyway V7 — `reviews` + Review domain/repository (TDD)

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__reviews.sql`
- Create: `backend/src/main/java/com/hireai/domain/biz/review/model/ReviewModel.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/review/repository/ReviewRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/review/ReviewJpaEntity.java` / `ReviewJpaRepository.java` / `ReviewRepositoryImpl.java`
- Test: `backend/src/test/java/com/hireai/review/ReviewRepositoryIntegrationTest.java`

- [ ] **Step 1: Write the migration**

```sql
-- V7: Reviews (SAD reviews table, seeded subset for Module 5 display). task_id is NULLABLE:
-- seeded demo reviews are not tied to resolved tasks because validation/settlement (Modules
-- 4/5) are unbuilt. When the real "review a settled task" flow lands, add the UNIQUE(task_id)
-- constraint from the SAD. Rating aggregates are computed from is_published rows only.
CREATE TABLE reviews (
    id               UUID PRIMARY KEY,
    task_id          UUID,
    client_id        UUID NOT NULL REFERENCES users (id),
    agent_id         UUID NOT NULL REFERENCES agents (id),
    rating           SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text      TEXT,
    builder_response TEXT,
    is_published     BOOLEAN NOT NULL DEFAULT TRUE,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reviews_agent_created ON reviews (agent_id, gmt_create DESC);

-- Seed 3 demo reviews for every agent existing at migration time, authored by the demo
-- client (V5 seed user). Agents registered later start at zero reviews — honest display.
INSERT INTO reviews (id, client_id, agent_id, rating, review_text)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000010', a.id, s.rating, s.review_text
FROM agents a
CROSS JOIN (VALUES
    (5, 'Output matched the declared spec exactly. Zero rework.'),
    (4, 'Fast turnaround. One minor formatting nit in the payload.'),
    (5, 'Consistent across repeat runs - would book directly again.')
) AS s(rating, review_text);
```

- [ ] **Step 2: Write the failing integration test**

(Same Testcontainers boilerplate as Task 2 Step 5 — container, datasource, dockerAvailable.)

```java
package com.hireai.review;

// imports as in AgentProfileRepositoryIntegrationTest, plus:
import com.hireai.domain.biz.review.model.ReviewModel;
import com.hireai.domain.biz.review.repository.ReviewRepository;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ReviewRepositoryIntegrationTest {

    // dockerAvailable() + @Container postgres + @DynamicPropertySource — copy from Task 2 test.

    @Autowired ReviewRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@t.local");
        return id;
    }

    private UUID seedAgent() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", owner, owner + "@t.local");
        UUID agentId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, reputation_score) "
                + "VALUES (?, ?, 'A', 'ACTIVE', 50.00)", agentId, owner);
        return agentId;
    }

    @Test
    void savesAndListsPublishedNewestFirst() {
        UUID client = seedClient();
        UUID agent = seedAgent();
        repository.save(ReviewModel.seeded(client, agent, 5, "great"));
        repository.save(ReviewModel.seeded(client, agent, 3, "ok"));

        List<ReviewModel> reviews = repository.findPublishedByAgentId(agent, 10);
        assertThat(reviews).hasSize(2);
        assertThat(reviews.get(0).reviewText()).isEqualTo("ok"); // newest first
    }

    @Test
    void respondPersistsBuilderResponse() {
        UUID client = seedClient();
        UUID agent = seedAgent();
        ReviewModel saved = repository.save(ReviewModel.seeded(client, agent, 4, "nice"));

        repository.save(saved.respond("Thanks — glad it helped!"));

        ReviewModel loaded = repository.findById(saved.id()).orElseThrow();
        assertThat(loaded.builderResponse()).isEqualTo("Thanks — glad it helped!");
    }
}
```

- [ ] **Step 3: Run — must fail to compile**

Run: `mvn -f backend/pom.xml -B test -Dtest=ReviewRepositoryIntegrationTest`
Expected: COMPILATION ERROR.

- [ ] **Step 4: Implement model + repository**

`ReviewModel.java`:

```java
package com.hireai.domain.biz.review.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's rating of an agent. In this slice reviews are SEEDED (taskId nullable) because
 * the rate-a-settled-task flow needs Modules 4/5. Builder may respond once content exists.
 * Immutable; respond() returns a copy.
 */
public final class ReviewModel {

    private final UUID id;
    private final UUID taskId;       // nullable for seeded reviews
    private final UUID clientId;
    private final UUID agentId;
    private final int rating;
    private final String reviewText;
    private final String builderResponse;
    private final boolean published;
    private final Instant createdAt;

    public ReviewModel(UUID id, UUID taskId, UUID clientId, UUID agentId, int rating,
                       String reviewText, String builderResponse, boolean published, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.clientId = clientId;
        this.agentId = agentId;
        this.rating = rating;
        this.reviewText = reviewText;
        this.builderResponse = builderResponse;
        this.published = published;
        this.createdAt = createdAt;
    }

    /** Factory for demo-seeded reviews (no task linkage). */
    public static ReviewModel seeded(UUID clientId, UUID agentId, int rating, String reviewText) {
        if (clientId == null || agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "client id and agent id are required");
        }
        if (rating < 1 || rating > 5) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "rating must be between 1 and 5");
        }
        return new ReviewModel(UUID.randomUUID(), null, clientId, agentId, rating,
                reviewText, null, true, Instant.now());
    }

    /** Builder responds to the review. */
    public ReviewModel respond(String response) {
        if (response == null || response.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "response must not be blank");
        }
        return new ReviewModel(id, taskId, clientId, agentId, rating,
                reviewText, response.trim(), published, createdAt);
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public UUID clientId() { return clientId; }
    public UUID agentId() { return agentId; }
    public int rating() { return rating; }
    public String reviewText() { return reviewText; }
    public String builderResponse() { return builderResponse; }
    public boolean published() { return published; }
    public Instant createdAt() { return createdAt; }
}
```

`ReviewRepository.java`:

```java
package com.hireai.domain.biz.review.repository;

import com.hireai.domain.biz.review.model.ReviewModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

    ReviewModel save(ReviewModel review);

    Optional<ReviewModel> findById(UUID reviewId);

    /** Published reviews for an agent, newest first. */
    List<ReviewModel> findPublishedByAgentId(UUID agentId, int limit);
}
```

`ReviewJpaEntity.java` — plain entity on `reviews` with columns `id, task_id, client_id, agent_id, rating (int), review_text, builder_response, is_published, gmt_create, gmt_modified`; same style as `AgentProfileJpaEntity` (no array/json columns). Full getters + protected no-arg + full-args constructor.

`ReviewJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.review;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewJpaRepository extends JpaRepository<ReviewJpaEntity, UUID> {

    List<ReviewJpaEntity> findByAgentIdAndIsPublishedTrueOrderByGmtCreateDesc(UUID agentId, Pageable page);
}
```

`ReviewRepositoryImpl.java` — `@Repository`, maps entity↔model both ways (update path: save() preserves `gmt_create` via findById like `AgentProfileRepositoryImpl`; sets `gmt_modified = now()`); `findPublishedByAgentId` uses `PageRequest.of(0, limit)`.

- [ ] **Step 5: Run — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest=ReviewRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V7__reviews.sql backend/src/main/java/com/hireai/domain/biz/review backend/src/main/java/com/hireai/infrastructure/repository/review backend/src/test/java/com/hireai/review
git commit -m "feat: reviews table (V7) with demo seed + review model and repository"
```

### Task 4: MediaStoragePort + SupabaseStorageClient

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/storage/MediaStoragePort.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/client/SupabaseStorageClient.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/hireai/storage/SupabaseStorageClientTest.java`

- [ ] **Step 1: Write the failing unit test** (MockRestServiceServer bound to a RestTemplate-backed RestClient)

```java
package com.hireai.storage;

import com.hireai.infrastructure.client.SupabaseStorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageClientTest {

    private MockRestServiceServer server;
    private SupabaseStorageClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new SupabaseStorageClient(RestClient.builder(restTemplate),
                "https://proj.supabase.co", "service-key-123", "agent-media");
    }

    @Test
    void uploadPutsObjectAndReturnsPublicUrl() {
        server.expect(requestTo("https://proj.supabase.co/storage/v1/object/agent-media/agents/a1/logo-x.png"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer service-key-123"))
                .andExpect(header("x-upsert", "true"))
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andRespond(withSuccess());

        String url = client.upload("agents/a1/logo-x.png", "image/png", new byte[]{1, 2, 3});

        assertThat(url).isEqualTo(
                "https://proj.supabase.co/storage/v1/object/public/agent-media/agents/a1/logo-x.png");
        server.verify();
    }

    @Test
    void deleteByUrlDerivesObjectKey() {
        server.expect(requestTo("https://proj.supabase.co/storage/v1/object/agent-media/agents/a1/logo-x.png"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer service-key-123"))
                .andRespond(withSuccess());

        client.deleteByUrl("https://proj.supabase.co/storage/v1/object/public/agent-media/agents/a1/logo-x.png");
        server.verify();
    }
}
```

- [ ] **Step 2: Run — must fail to compile**

Run: `mvn -f backend/pom.xml -B test -Dtest=SupabaseStorageClientTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement port + client**

`MediaStoragePort.java`:

```java
package com.hireai.application.port.storage;

/**
 * Outbound port for storing public agent media. Backend-only: the Supabase service key never
 * leaves the server (spec §6). Implementations must return a publicly readable URL.
 */
public interface MediaStoragePort {

    /** Uploads (upserting) and returns the public URL. */
    String upload(String objectKey, String contentType, byte[] bytes);

    /** Best-effort delete of a previously returned public URL. Never throws on missing object. */
    void deleteByUrl(String publicUrl);
}
```

`SupabaseStorageClient.java`:

```java
package com.hireai.infrastructure.client;

import com.hireai.application.port.storage.MediaStoragePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Supabase Storage REST adapter. Object layout: {bucket}/agents/{agentId}/{kind}-{uuid}.{ext}.
 * Upload = POST /storage/v1/object/{bucket}/{key} (x-upsert) with the service key; public read
 * URL = /storage/v1/object/public/{bucket}/{key} (bucket is public-read, write is server-only).
 */
@Component
@Slf4j
public class SupabaseStorageClient implements MediaStoragePort {

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;
    private final String bucket;

    public SupabaseStorageClient(RestClient.Builder restClientBuilder,
                                 @Value("${hireai.storage.supabase-url:}") String baseUrl,
                                 @Value("${hireai.storage.service-key:}") String serviceKey,
                                 @Value("${hireai.storage.bucket:agent-media}") String bucket) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.serviceKey = serviceKey;
        this.bucket = bucket;
    }

    @Override
    public String upload(String objectKey, String contentType, byte[] bytes) {
        requireConfigured();
        restClient.post()
                .uri(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectKey))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(contentType))
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
        return publicUrl(objectKey);
    }

    @Override
    public void deleteByUrl(String publicUrl) {
        requireConfigured();
        String marker = "/storage/v1/object/public/" + bucket + "/";
        int idx = publicUrl == null ? -1 : publicUrl.indexOf(marker);
        if (idx < 0) {
            log.warn("Ignoring delete for unrecognised media URL: {}", publicUrl);
            return;
        }
        String objectKey = publicUrl.substring(idx + marker.length());
        try {
            restClient.delete()
                    .uri(URI.create(baseUrl + "/storage/v1/object/" + bucket + "/" + objectKey))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Best-effort media delete failed for {}: {}", objectKey, ex.getMessage());
        }
    }

    private String publicUrl(String objectKey) {
        return baseUrl + "/storage/v1/object/public/" + bucket + "/" + objectKey;
    }

    private void requireConfigured() {
        if (baseUrl.isBlank() || serviceKey.isBlank()) {
            throw new IllegalStateException(
                    "Supabase storage is not configured (SUPABASE_URL / SUPABASE_SERVICE_KEY)");
        }
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url == null ? "" : url;
    }
}
```

Append to `backend/src/main/resources/application.yml` under the existing `hireai:` block, and add multipart limits under `spring:`:

```yaml
# under spring:
  servlet:
    multipart:
      max-file-size: 2MB
      max-request-size: 5MB

# under hireai:
  storage:
    # Supabase Storage (public-read bucket; uploads happen ONLY server-side with the service key).
    supabase-url: ${SUPABASE_URL:}
    service-key: ${SUPABASE_SERVICE_KEY:}
    bucket: ${SUPABASE_STORAGE_BUCKET:agent-media}
```

- [ ] **Step 4: Run — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest=SupabaseStorageClientTest`
Expected: PASS (2 tests).

- [ ] **Step 5: One-time environment setup (manual, document in runbook later — Task 22)**

Add to `backend/.env` (git-ignored — NEVER commit):

```
SUPABASE_URL=https://kecgkmezjsqjeftcrxjc.supabase.co
SUPABASE_SERVICE_KEY=<service_role key from Supabase dashboard → Settings → API>
```

Create the bucket (once):

```bash
curl -X POST "https://kecgkmezjsqjeftcrxjc.supabase.co/storage/v1/bucket" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_KEY" -H "Content-Type: application/json" \
  -d '{"id":"agent-media","name":"agent-media","public":true}'
```

Expected: `{"name":"agent-media"}` (or 409 if it already exists — fine).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/port/storage backend/src/main/java/com/hireai/infrastructure/client/SupabaseStorageClient.java backend/src/main/resources/application.yml backend/src/test/java/com/hireai/storage
git commit -m "feat: Supabase Storage media port (server-side service key, public-read bucket)"
```

# Phase 2 — Catalogue read API

### Task 5: CatalogueQueryPort + JdbcCatalogueQueryDao (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/query/CatalogueQueryPort.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcCatalogueQueryDao.java`
- Test: `backend/src/test/java/com/hireai/catalogue/CatalogueQueryDaoIntegrationTest.java`

- [ ] **Step 1: Define the port (contract first — the DAO test compiles against it)**

```java
package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side port for the public catalogue (CQRS read over agents/profiles/reviews/tasks).
 * Only ACTIVE + listed agents are ever returned. Implementations own the SQL; sort keys are
 * whitelisted here so user input never reaches ORDER BY.
 */
public interface CatalogueQueryPort {

    List<String> SORT_KEYS = List.of("hot", "rating", "price_asc", "price_desc", "newest");

    List<AgentCardRow> searchCards(String q, String category, String sort, int page, int size);

    Optional<AgentProfileRow> findProfile(UUID agentId);

    List<CategoryCountRow> categoryCounts();

    List<ReviewRow> reviewsForAgent(UUID agentId, int limit);

    record AgentCardRow(UUID id, String name, String builderName, BigDecimal reputationScore,
                        String tagline, String logoUrl, String coverUrl, boolean featured,
                        List<String> categories, BigDecimal price, int maxExecutionSeconds,
                        BigDecimal ratingAvg, int ratingCount, int requestCount, Instant createdAt) {
    }

    record AgentProfileRow(AgentCardRow card, String description, String sampleOutput,
                           List<String> galleryUrls, String outputSpecJson,
                           int completedCount, Double avgTurnaroundSeconds) {
    }

    record CategoryCountRow(String category, int agentCount) {
    }

    record ReviewRow(UUID id, int rating, String reviewText, String builderResponse,
                     String author, Instant createdAt) {
    }
}
```

- [ ] **Step 2: Write the failing integration test**

Boilerplate (container/datasource/dockerAvailable) as in Task 2. Seed helper inserts a full agent (user → agent → version → profile) plus optional reviews/tasks:

```java
package com.hireai.catalogue;

// standard imports + :
import com.hireai.application.port.query.CatalogueQueryPort;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class CatalogueQueryDaoIntegrationTest {

    // dockerAvailable() + @Container postgres + @DynamicPropertySource — copy from Task 2 test.

    @Autowired CatalogueQueryPort catalogue;
    @Autowired JdbcTemplate jdbc;

    private UUID seedAgent(String name, String builderEmail, String status, boolean listed,
                           String category, String price, boolean featured) {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", owner, builderEmail);
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) "
                + "VALUES (?, ?, ?, ?, ?, 60.00)", agentId, owner, name, status, versionId);
        jdbc.update("INSERT INTO agent_versions (id, agent_id, version_number, output_spec, "
                + "capability_categories, webhook_url, max_execution_seconds, price) "
                + "VALUES (?, ?, 1, '{\"format\":\"JSON\"}'::jsonb, ARRAY[?], 'https://a.example/run', 60, ?::numeric)",
                versionId, agentId, category, price);
        jdbc.update("UPDATE agent_profiles SET is_listed = ?, is_featured = ?, tagline = 'T' "
                + "WHERE agent_id = ?", listed, featured, agentId);
        // V6 backfill only covers rows existing AT migration time — insert the profile here:
        jdbc.update("INSERT INTO agent_profiles (agent_id, is_listed, is_featured, tagline) "
                + "VALUES (?, ?, ?, 'T') ON CONFLICT (agent_id) DO UPDATE SET is_listed = EXCLUDED.is_listed, "
                + "is_featured = EXCLUDED.is_featured", agentId, listed, featured);
        return agentId;
    }

    @Test
    void onlyActiveListedAgentsAppear() {
        seedAgent("Visible", "alice@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        seedAgent("Unlisted", "bob@x.com", "ACTIVE", false, "summarisation", "10.00", false);
        seedAgent("Inactive", "carol@x.com", "PENDING_VERIFICATION", true, "summarisation", "10.00", false);

        var cards = catalogue.searchCards("", "", "newest", 0, 50);
        assertThat(cards).extracting(CatalogueQueryPort.AgentCardRow::name).containsExactly("Visible");
    }

    @Test
    void searchMatchesAgentNameOrBuilderName() {
        seedAgent("Summariser Bot", "alice@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        seedAgent("Translator", "bobbuilder@x.com", "ACTIVE", true, "translation", "10.00", false);

        assertThat(catalogue.searchCards("summar", "", "newest", 0, 50)).hasSize(1);
        assertThat(catalogue.searchCards("bobbuilder", "", "newest", 0, 50))
                .extracting(CatalogueQueryPort.AgentCardRow::name).containsExactly("Translator");
    }

    @Test
    void categoryFilterAndCounts() {
        seedAgent("A", "a@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        seedAgent("B", "b@x.com", "ACTIVE", true, "translation", "10.00", false);

        assertThat(catalogue.searchCards("", "translation", "newest", 0, 50)).hasSize(1);
        assertThat(catalogue.categoryCounts())
                .extracting(CatalogueQueryPort.CategoryCountRow::category)
                .containsExactlyInAnyOrder("summarisation", "translation");
    }

    @Test
    void featuredFloatsToTopOnHotSort() {
        seedAgent("Plain", "a@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        seedAgent("Pinned", "b@x.com", "ACTIVE", true, "summarisation", "10.00", true);

        var cards = catalogue.searchCards("", "", "hot", 0, 50);
        assertThat(cards.get(0).name()).isEqualTo("Pinned");
    }

    @Test
    void profileIncludesContentAndAbsentForUnlisted() {
        UUID visible = seedAgent("Visible", "a@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        UUID unlisted = seedAgent("Hidden", "b@x.com", "ACTIVE", false, "summarisation", "10.00", false);
        jdbc.update("UPDATE agent_profiles SET description = 'It summarises' WHERE agent_id = ?", visible);

        assertThat(catalogue.findProfile(visible)).hasValueSatisfying(p -> {
            assertThat(p.description()).isEqualTo("It summarises");
            assertThat(p.card().name()).isEqualTo("Visible");
        });
        assertThat(catalogue.findProfile(unlisted)).isEmpty();
    }

    @Test
    void reviewsForAgentNewestFirstWithAuthor() {
        UUID agent = seedAgent("A", "a@x.com", "ACTIVE", true, "summarisation", "10.00", false);
        UUID client = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, 'reviewer@x.com', 'CLIENT')", client);
        jdbc.update("INSERT INTO reviews (id, client_id, agent_id, rating, review_text) "
                + "VALUES (gen_random_uuid(), ?, ?, 5, 'great')", client, agent);

        var reviews = catalogue.reviewsForAgent(agent, 10);
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).author()).isEqualTo("reviewer");
    }
}
```

> Note: keep ONLY the `INSERT ... ON CONFLICT` profile statement in `seedAgent` (drop the preceding UPDATE — it's redundant; shown above for clarity of intent, delete it when implementing).

- [ ] **Step 3: Run — must fail (no implementation bean)**

Run: `mvn -f backend/pom.xml -B test -Dtest=CatalogueQueryDaoIntegrationTest`
Expected: FAIL — `NoSuchBeanDefinitionException: CatalogueQueryPort`.

- [ ] **Step 4: Implement the DAO**

```java
package com.hireai.infrastructure.repository.catalogue;

import com.hireai.application.port.query.CatalogueQueryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only catalogue projection over agents/profiles/versions/reviews/tasks. ORDER BY comes
 * from the SORTS whitelist — user input is bound as parameters only. hot = featured pin, then
 * reputation*0.5 + 14d-request-count*8 + (+10 if a request in the last 3 days)  (spec §4.5).
 */
@Repository
public class JdbcCatalogueQueryDao implements CatalogueQueryPort {

    private static final Map<String, String> SORTS = Map.of(
            "hot", "is_featured DESC, hot_score DESC, gmt_create DESC",
            "rating", "rating_avg DESC NULLS LAST, rating_count DESC, gmt_create DESC",
            "price_asc", "price ASC, gmt_create DESC",
            "price_desc", "price DESC, gmt_create DESC",
            "newest", "gmt_create DESC");

    private static final String CARD_SELECT = """
            SELECT a.id, a.name, split_part(u.email, '@', 1) AS builder_name,
                   a.reputation_score, a.gmt_create,
                   p.tagline, p.logo_url, p.cover_url, p.is_featured,
                   v.capability_categories, v.price, v.max_execution_seconds,
                   r.rating_avg, COALESCE(r.rating_count, 0) AS rating_count,
                   COALESCE(t.request_count, 0) AS request_count,
                   (a.reputation_score * 0.5
                    + COALESCE(t.recent_count, 0) * 8
                    + CASE WHEN t.last_request_at > now() - INTERVAL '3 days' THEN 10 ELSE 0 END
                   ) AS hot_score
            FROM agents a
            JOIN users u          ON u.id = a.owner_id
            JOIN agent_profiles p ON p.agent_id = a.id
            JOIN agent_versions v ON v.id = a.current_version_id
            LEFT JOIN (
                SELECT agent_id, AVG(rating)::numeric(3,2) AS rating_avg, COUNT(*) AS rating_count
                FROM reviews WHERE is_published GROUP BY agent_id
            ) r ON r.agent_id = a.id
            LEFT JOIN (
                SELECT av.agent_id,
                       COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE tk.gmt_create > now() - INTERVAL '14 days') AS recent_count,
                       MAX(tk.gmt_create) AS last_request_at
                FROM tasks tk JOIN agent_versions av ON av.id = tk.agent_version_id
                GROUP BY av.agent_id
            ) t ON t.agent_id = a.id
            WHERE a.status = 'ACTIVE' AND p.is_listed
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogueQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AgentCardRow> searchCards(String q, String category, String sort, int page, int size) {
        String orderBy = SORTS.getOrDefault(sort == null ? "hot" : sort, SORTS.get("hot"));
        String sql = CARD_SELECT + """
                  AND (:q = '' OR a.name ILIKE '%' || :q || '%'
                       OR split_part(u.email, '@', 1) ILIKE '%' || :q || '%')
                  AND (:category = '' OR :category = ANY(v.capability_categories))
                ORDER BY """ + " " + orderBy + " LIMIT :size OFFSET :offset";
        var params = new MapSqlParameterSource()
                .addValue("q", q == null ? "" : q.trim())
                .addValue("category", category == null ? "" : category.trim().toLowerCase())
                .addValue("size", size)
                .addValue("offset", Math.max(page, 0) * size);
        return jdbc.query(sql, params, cardMapper());
    }

    @Override
    public Optional<AgentProfileRow> findProfile(UUID agentId) {
        String sql = """
                SELECT a.id, a.name, split_part(u.email, '@', 1) AS builder_name,
                       a.reputation_score, a.gmt_create,
                       p.tagline, p.logo_url, p.cover_url, p.is_featured,
                       p.description, p.sample_output, p.gallery_urls,
                       v.capability_categories, v.price, v.max_execution_seconds,
                       v.output_spec AS output_spec_json,
                       r.rating_avg, COALESCE(r.rating_count, 0) AS rating_count,
                       COALESCE(s.request_count, 0) AS request_count,
                       COALESCE(s.completed_count, 0) AS completed_count,
                       s.avg_turnaround_seconds
                FROM agents a
                JOIN users u          ON u.id = a.owner_id
                JOIN agent_profiles p ON p.agent_id = a.id
                JOIN agent_versions v ON v.id = a.current_version_id
                LEFT JOIN (
                    SELECT agent_id, AVG(rating)::numeric(3,2) AS rating_avg, COUNT(*) AS rating_count
                    FROM reviews WHERE is_published GROUP BY agent_id
                ) r ON r.agent_id = a.id
                LEFT JOIN (
                    SELECT av.agent_id,
                           COUNT(*) AS request_count,
                           COUNT(*) FILTER (WHERE tk.status IN ('RESULT_RECEIVED','RESOLVED')) AS completed_count,
                           AVG(EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create))) AS avg_turnaround_seconds
                    FROM tasks tk
                    JOIN agent_versions av ON av.id = tk.agent_version_id
                    LEFT JOIN task_results tr ON tr.task_id = tk.id
                    GROUP BY av.agent_id
                ) s ON s.agent_id = a.id
                WHERE a.id = :agentId AND a.status = 'ACTIVE' AND p.is_listed
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId);
        List<AgentProfileRow> rows = jdbc.query(sql, params, (rs, i) -> new AgentProfileRow(
                mapCard(rs),
                rs.getString("description"),
                rs.getString("sample_output"),
                stringList(rs.getArray("gallery_urls")),
                rs.getString("output_spec_json"),
                rs.getInt("completed_count"),
                (Double) rs.getObject("avg_turnaround_seconds")));
        return rows.stream().findFirst();
    }

    @Override
    public List<CategoryCountRow> categoryCounts() {
        String sql = """
                SELECT cat AS category, COUNT(DISTINCT a.id) AS agent_count
                FROM agents a
                JOIN agent_profiles p ON p.agent_id = a.id
                JOIN agent_versions v ON v.id = a.current_version_id
                CROSS JOIN LATERAL unnest(v.capability_categories) AS cat
                WHERE a.status = 'ACTIVE' AND p.is_listed
                GROUP BY cat
                ORDER BY agent_count DESC, cat ASC
                """;
        return jdbc.query(sql, Map.of(),
                (rs, i) -> new CategoryCountRow(rs.getString("category"), rs.getInt("agent_count")));
    }

    @Override
    public List<ReviewRow> reviewsForAgent(UUID agentId, int limit) {
        String sql = """
                SELECT r.id, r.rating, r.review_text, r.builder_response,
                       split_part(u.email, '@', 1) AS author, r.gmt_create
                FROM reviews r JOIN users u ON u.id = r.client_id
                WHERE r.agent_id = :agentId AND r.is_published
                ORDER BY r.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> new ReviewRow(
                rs.getObject("id", UUID.class), rs.getInt("rating"), rs.getString("review_text"),
                rs.getString("builder_response"), rs.getString("author"),
                rs.getTimestamp("gmt_create").toInstant()));
    }

    private RowMapper<AgentCardRow> cardMapper() {
        return (rs, i) -> mapCard(rs);
    }

    private AgentCardRow mapCard(ResultSet rs) throws SQLException {
        return new AgentCardRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("builder_name"),
                rs.getBigDecimal("reputation_score"), rs.getString("tagline"),
                rs.getString("logo_url"), rs.getString("cover_url"), rs.getBoolean("is_featured"),
                stringList(rs.getArray("capability_categories")), rs.getBigDecimal("price"),
                rs.getInt("max_execution_seconds"), rs.getBigDecimal("rating_avg"),
                rs.getInt("rating_count"), rs.getInt("request_count"),
                rs.getTimestamp("gmt_create").toInstant());
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
```

- [ ] **Step 5: Run — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest=CatalogueQueryDaoIntegrationTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/port/query backend/src/main/java/com/hireai/infrastructure/repository/catalogue backend/src/test/java/com/hireai/catalogue
git commit -m "feat: catalogue read DAO (search, category counts, profile, reviews, hot ranking)"
```

### Task 6: Catalogue app service + controller + DTOs (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/catalogue/CatalogueReadAppService.java` + `impl/CatalogueReadAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/catalogue/CatalogueController.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/catalogue/dto/AgentCardDTO.java`, `AgentProfileDTO.java`, `CategoryCountDTO.java`
- Test: `backend/src/test/java/com/hireai/controller/biz/catalogue/CatalogueControllerTest.java`

- [ ] **Step 1: Write the failing controller slice test** (pattern: `TaskControllerTest`)

```java
package com.hireai.controller.biz.catalogue;

import com.hireai.application.biz.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CatalogueController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class CatalogueControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean CatalogueReadAppService catalogueReadAppService;

    private AgentCardRow card(String name) {
        return new AgentCardRow(UUID.randomUUID(), name, "alice", new BigDecimal("60.00"),
                "Fast summaries", null, null, false, List.of("summarisation"),
                new BigDecimal("10.00"), 60, new BigDecimal("4.50"), 3, 7, Instant.now());
    }

    @Test
    void listPassesFiltersAndReturnsCards() throws Exception {
        when(catalogueReadAppService.search(eq("sum"), eq("summarisation"), eq("rating"), eq(0), eq(20)))
                .thenReturn(List.of(card("Summariser Bot")));

        mockMvc.perform(get("/api/catalogue/agents")
                        .param("q", "sum").param("category", "summarisation")
                        .param("sort", "rating").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Summariser Bot"))
                .andExpect(jsonPath("$.data[0].builderName").value("alice"))
                .andExpect(jsonPath("$.data[0].ratingAvg").value(4.5))
                .andExpect(jsonPath("$.data[0].requestCount").value(7));
    }

    @Test
    void profileReturns404WhenNotListed() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalogueReadAppService.getProfile(id))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "Agent not found: " + id));

        mockMvc.perform(get("/api/catalogue/agents/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void categoriesEndpointReturnsCounts() throws Exception {
        when(catalogueReadAppService.categories()).thenReturn(
                List.of(new com.hireai.application.port.query.CatalogueQueryPort.CategoryCountRow("summarisation", 2)));

        mockMvc.perform(get("/api/catalogue/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].category").value("summarisation"))
                .andExpect(jsonPath("$.data[0].agentCount").value(2));
    }
}
```

- [ ] **Step 2: Run — must fail to compile**

Run: `mvn -f backend/pom.xml -B test -Dtest=CatalogueControllerTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement service + DTOs + controller**

`CatalogueReadAppService.java`:

```java
package com.hireai.application.biz.catalogue;

import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.application.port.query.CatalogueQueryPort.CategoryCountRow;
import com.hireai.application.port.query.CatalogueQueryPort.ReviewRow;

import java.util.List;
import java.util.UUID;

/** Public catalogue reads. No ownership scoping — visibility is ACTIVE + listed, enforced below. */
public interface CatalogueReadAppService {

    List<AgentCardRow> search(String q, String category, String sort, int page, int size);

    /** @throws com.hireai.domain.shared.exception.DomainException NOT_FOUND when absent/unlisted. */
    AgentProfileRow getProfile(UUID agentId);

    List<CategoryCountRow> categories();

    List<ReviewRow> reviews(UUID agentId);
}
```

`impl/CatalogueReadAppServiceImpl.java`:

```java
package com.hireai.application.biz.catalogue.impl;

import com.hireai.application.biz.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CatalogueReadAppServiceImpl implements CatalogueReadAppService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int REVIEWS_LIMIT = 20;

    private final CatalogueQueryPort catalogueQueryPort;

    @Override
    public List<CatalogueQueryPort.AgentCardRow> search(String q, String category, String sort,
                                                        int page, int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return catalogueQueryPort.searchCards(q, category, sort, Math.max(page, 0), bounded);
    }

    @Override
    public CatalogueQueryPort.AgentProfileRow getProfile(UUID agentId) {
        return catalogueQueryPort.findProfile(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Agent not found: " + agentId));
    }

    @Override
    public List<CatalogueQueryPort.CategoryCountRow> categories() {
        return catalogueQueryPort.categoryCounts();
    }

    @Override
    public List<CatalogueQueryPort.ReviewRow> reviews(UUID agentId) {
        return catalogueQueryPort.reviewsForAgent(agentId, REVIEWS_LIMIT);
    }
}
```

DTOs (records; follow `AgentDTO`'s nested-record style):

```java
// dto/AgentCardDTO.java
package com.hireai.controller.biz.catalogue.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentCardDTO(UUID id, String name, String builderName, String tagline,
                           String logoUrl, String coverUrl, List<String> categories,
                           BigDecimal price, int maxExecutionSeconds, BigDecimal reputationScore,
                           BigDecimal ratingAvg, int ratingCount, int requestCount,
                           boolean featured, Instant createdAt) {
}
```

```java
// dto/AgentProfileDTO.java
package com.hireai.controller.biz.catalogue.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgentProfileDTO(AgentCardDTO card, String description, String sampleOutput,
                              List<String> galleryUrls, OutputSpecDTO outputSpec,
                              StatsDTO stats, List<ReviewDTO> reviews) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }

    public record StatsDTO(int requestCount, int completedCount, Double successRate,
                           Double avgTurnaroundSeconds) {
    }

    public record ReviewDTO(UUID id, int rating, String reviewText, String builderResponse,
                            String author, Instant createdAt) {
    }
}
```

```java
// dto/CategoryCountDTO.java
package com.hireai.controller.biz.catalogue.dto;

public record CategoryCountDTO(String category, int agentCount) {
}
```

`CatalogueController.java` — converts rows→DTOs inline (small, deliberate boundary; parse `outputSpecJson` with the existing `OutputSpecJsonMapper` and map via `spec.format().name()`):

```java
package com.hireai.controller.biz.catalogue;

import com.hireai.application.biz.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort.AgentCardRow;
import com.hireai.application.port.query.CatalogueQueryPort.AgentProfileRow;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.catalogue.dto.AgentCardDTO;
import com.hireai.controller.biz.catalogue.dto.AgentProfileDTO;
import com.hireai.controller.biz.catalogue.dto.CategoryCountDTO;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.infrastructure.repository.task.OutputSpecJsonMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public catalogue surface (Module 6). Authenticated like everything else, but NOT owner-scoped:
 * any signed-in user can browse. Only ACTIVE + listed agents are reachable; owner-private fields
 * (webhook URL, owner id) are deliberately absent from the DTOs (spec §6).
 */
@RestController
@RequestMapping("/api/catalogue")
public class CatalogueController extends BaseController {

    private final CatalogueReadAppService readAppService;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public CatalogueController(CatalogueReadAppService readAppService,
                               OutputSpecJsonMapper outputSpecJsonMapper) {
        this.readAppService = readAppService;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @GetMapping("/agents")
    public WebResult<List<AgentCardDTO>> list(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<AgentCardDTO> cards = readAppService.search(q, category, sort, page, size)
                .stream().map(CatalogueController::toCardDTO).toList();
        return ok(cards);
    }

    @GetMapping("/agents/{agentId}")
    public WebResult<AgentProfileDTO> profile(@PathVariable("agentId") UUID agentId) {
        AgentProfileRow row = readAppService.getProfile(agentId);
        OutputSpec spec = outputSpecJsonMapper.fromJson(row.outputSpecJson());
        int requests = row.card().requestCount();
        Double successRate = requests == 0 ? null : (double) row.completedCount() / requests;
        AgentProfileDTO dto = new AgentProfileDTO(
                toCardDTO(row.card()), row.description(), row.sampleOutput(), row.galleryUrls(),
                new AgentProfileDTO.OutputSpecDTO(spec.format().name(), spec.schema(),
                        spec.acceptanceCriteria()),
                new AgentProfileDTO.StatsDTO(requests, row.completedCount(), successRate,
                        row.avgTurnaroundSeconds()),
                readAppService.reviews(agentId).stream()
                        .map(r -> new AgentProfileDTO.ReviewDTO(r.id(), r.rating(), r.reviewText(),
                                r.builderResponse(), r.author(), r.createdAt()))
                        .toList());
        return ok(dto);
    }

    @GetMapping("/categories")
    public WebResult<List<CategoryCountDTO>> categories() {
        return ok(readAppService.categories().stream()
                .map(c -> new CategoryCountDTO(c.category(), c.agentCount()))
                .toList());
    }

    private static AgentCardDTO toCardDTO(AgentCardRow c) {
        return new AgentCardDTO(c.id(), c.name(), c.builderName(), c.tagline(), c.logoUrl(),
                c.coverUrl(), c.categories(), c.price(), c.maxExecutionSeconds(),
                c.reputationScore(), c.ratingAvg(), c.ratingCount(), c.requestCount(),
                c.featured(), c.createdAt());
    }
}
```

> If `OutputSpecJsonMapper` lives in a package that makes this import awkward (check `backend/src/main/java/com/hireai/infrastructure/repository/task/`), keep the import as-is — controllers already transitively depend on infrastructure via Spring wiring, and this mirrors how `TaskRepositoryImpl` uses it. If its constructor needs an ObjectMapper, it's already a Spring bean — inject it.

- [ ] **Step 4: Run — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest=CatalogueControllerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Full backend suite — must stay green**

Run: `mvn -f backend/pom.xml -B test`
Expected: all ~158 existing + new tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/catalogue backend/src/main/java/com/hireai/controller/biz/catalogue backend/src/test/java/com/hireai/controller/biz/catalogue
git commit -m "feat: public catalogue API - search, agent profile, categories"
```

---

# Phase 3 — Marketplace frontend

### Task 7: Frontend types, `apiUpload`, MSW catalogue handlers

**Files:**
- Modify: `frontend/lib/types.ts`, `frontend/lib/api.ts`, `frontend/test/msw/handlers.ts`
- Test: `frontend/lib/api.test.ts` (append)

- [ ] **Step 1: Add types** (append to `frontend/lib/types.ts`)

```typescript
// ── Catalogue (public discovery) ──

export interface AgentCardDTO {
  id: string;
  name: string;
  builderName: string;
  tagline: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  categories: string[];
  price: number;
  maxExecutionSeconds: number;
  reputationScore: number;
  ratingAvg: number | null;
  ratingCount: number;
  requestCount: number;
  featured: boolean;
  createdAt: string;
}

export interface CatalogueReviewDTO {
  id: string;
  rating: number;
  reviewText: string | null;
  builderResponse: string | null;
  author: string;
  createdAt: string;
}

export interface CatalogueStatsDTO {
  requestCount: number;
  completedCount: number;
  successRate: number | null;
  avgTurnaroundSeconds: number | null;
}

export interface AgentProfileDTO {
  card: AgentCardDTO;
  description: string | null;
  sampleOutput: string | null;
  galleryUrls: string[];
  outputSpec: OutputSpecDTO;
  stats: CatalogueStatsDTO;
  reviews: CatalogueReviewDTO[];
}

export interface CategoryCountDTO {
  category: string;
  agentCount: number;
}

// ── Direct booking ──

export interface DirectBookRequest {
  title: string;
  description: string;
  budget: number;
  agentId: string;
}

export type CatalogueSort = "hot" | "rating" | "price_asc" | "price_desc" | "newest";
```

- [ ] **Step 2: Refactor `api.ts` — extract envelope handling, add `apiUpload`**

In `frontend/lib/api.ts`, extract the body of `api()` after the `fetch` into a shared helper and add `apiUpload`. Final shape (keep `api()` behaviour byte-identical):

```typescript
async function parseEnvelope<T>(res: Response): Promise<T> {
  if (res.status === 401) {
    handleUnauthorized();
    throw new ApiError("UNAUTHORIZED", "Session expired", 401);
  }
  let body: WebResult<T> | null = null;
  try {
    body = (await res.json()) as WebResult<T>;
  } catch {
    throw new ApiError("UNKNOWN", res.statusText || "Request failed", res.status);
  }
  if (!res.ok || !body.success) {
    throw new ApiError(body.code || "UNKNOWN", body.message || res.statusText, res.status);
  }
  return body.data as T;
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = readToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(init?.headers as Record<string, string> | undefined),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`/api${path}`, { ...init, headers });
  return parseEnvelope<T>(res);
}

/**
 * Multipart variant for file uploads: NO Content-Type header (the browser sets the
 * multipart boundary itself); same bearer token + WebResult envelope handling.
 */
export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  const token = readToken();
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`/api${path}`, { method: "POST", body: form, headers });
  return parseEnvelope<T>(res);
}
```

- [ ] **Step 3: Append an `apiUpload` test to `frontend/lib/api.test.ts`** (mirror the file's existing MSW/test style — read it first; the test below assumes the file already spins up the shared `server`)

```typescript
import { http, HttpResponse } from "msw";
// ... existing imports, including { apiUpload } from "./api"

it("apiUpload posts multipart without a JSON content-type and parses the envelope", async () => {
  server.use(
    http.post("*/api/agents/a-1/media", async ({ request }) => {
      const contentType = request.headers.get("content-type") ?? "";
      if (!contentType.startsWith("multipart/form-data")) {
        return HttpResponse.json(
          { success: false, code: "BAD", message: "wrong content type", data: null },
          { status: 400 },
        );
      }
      return HttpResponse.json({ success: true, code: "OK", message: null, data: { ok: true } });
    }),
  );
  localStorage.setItem("hireai.token", "t");
  const form = new FormData();
  form.append("kind", "logo");
  form.append("file", new File([new Uint8Array([1])], "x.png", { type: "image/png" }));
  await expect(apiUpload("/agents/a-1/media", form)).resolves.toEqual({ ok: true });
});
```

- [ ] **Step 4: Add MSW catalogue fixtures + handlers** (append to `frontend/test/msw/handlers.ts`)

```typescript
const CARD = {
  id: "ag-1",
  name: "Summariser Bot",
  builderName: "builder",
  tagline: "Fast, spec-true summaries",
  logoUrl: null,
  coverUrl: null,
  categories: ["summarisation"],
  price: 10,
  maxExecutionSeconds: 60,
  reputationScore: 60,
  ratingAvg: 4.5,
  ratingCount: 3,
  requestCount: 7,
  featured: true,
  createdAt: "2026-06-06T10:00:00Z",
};

// Append inside the handlers array:
  http.get("*/api/catalogue/agents", ({ request }) => {
    const url = new URL(request.url);
    const q = url.searchParams.get("q") ?? "";
    const category = url.searchParams.get("category") ?? "";
    if (q && !CARD.name.toLowerCase().includes(q.toLowerCase())) return ok([]);
    if (category && !CARD.categories.includes(category)) return ok([]);
    return ok([CARD]);
  }),
  http.get("*/api/catalogue/categories", () =>
    ok([
      { category: "summarisation", agentCount: 1 },
      { category: "translation", agentCount: 2 },
    ]),
  ),
  http.get("*/api/catalogue/agents/:id", ({ params }) =>
    params.id === "ag-1"
      ? ok({
          card: CARD,
          description: "Summarises long documents into crisp JSON briefs.",
          sampleOutput: '{"summary":"Example output"}',
          galleryUrls: [],
          outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
          stats: { requestCount: 7, completedCount: 6, successRate: 0.857, avgTurnaroundSeconds: 42 },
          reviews: [
            {
              id: "rev-1",
              rating: 5,
              reviewText: "Output matched the spec exactly.",
              builderResponse: null,
              author: "client",
              createdAt: "2026-06-05T10:00:00Z",
            },
          ],
        })
      : fail("NOT_FOUND", "Agent not found", 404),
  ),
  http.post("*/api/tasks/direct", async ({ request }) => {
    const body = (await request.json()) as { title: string; budget: number; agentId: string };
    if (body.budget < 10) return fail("VALIDATION_ERROR", "budget below agent price", 400);
    return ok({
      id: "t-direct-1",
      clientId: "u-1",
      title: body.title,
      description: "d",
      budget: body.budget,
      status: "SUBMITTED",
      outputSpec: { format: "JSON", schema: "{}", acceptanceCriteria: "valid JSON" },
      createdAt: "2026-06-06T10:00:00Z",
    });
  }),
```

- [ ] **Step 5: Run frontend checks**

Run (in `frontend/`): `npx vitest run` then `npx tsc --noEmit`
Expected: all existing tests + the new `apiUpload` test pass; tsc clean.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/types.ts frontend/lib/api.ts frontend/lib/api.test.ts frontend/test/msw/handlers.ts
git commit -m "feat: catalogue types, multipart apiUpload helper, MSW catalogue fixtures"
```

### Task 8: `RatingStars`, `AgentCard`, `CategoryBar` components (TDD)

**Files:**
- Create: `frontend/components/RatingStars.tsx`, `frontend/components/AgentCard.tsx`, `frontend/components/CategoryBar.tsx`
- Test: `frontend/components/AgentCard.test.tsx`

- [ ] **Step 1: Write the failing component test**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { AgentCard } from "@/components/AgentCard";
import type { AgentCardDTO } from "@/lib/types";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));

const card: AgentCardDTO = {
  id: "ag-1",
  name: "Summariser Bot",
  builderName: "builder",
  tagline: "Fast, spec-true summaries",
  logoUrl: null,
  coverUrl: null,
  categories: ["summarisation"],
  price: 10,
  maxExecutionSeconds: 60,
  reputationScore: 60,
  ratingAvg: 4.5,
  ratingCount: 3,
  requestCount: 7,
  featured: true,
  createdAt: "2026-06-06T10:00:00Z",
};

describe("AgentCard", () => {
  it("renders name, builder, tagline, rating, price and links to the storefront", () => {
    render(<AgentCard agent={card} />);
    expect(screen.getByText("Summariser Bot")).toBeInTheDocument();
    expect(screen.getByText(/by builder/i)).toBeInTheDocument();
    expect(screen.getByText("Fast, spec-true summaries")).toBeInTheDocument();
    expect(screen.getByText("4.5")).toBeInTheDocument();
    expect(screen.getByText(/10\s*cr/i)).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute("href", "/client/agents/ag-1");
    expect(screen.getByText(/hot/i)).toBeInTheDocument(); // featured chip
  });

  it("shows a no-reviews state when ratingCount is 0", () => {
    render(<AgentCard agent={{ ...card, ratingAvg: null, ratingCount: 0, featured: false }} />);
    expect(screen.getByText(/no reviews yet/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run — must fail**

Run (in `frontend/`): `npx vitest run components/AgentCard.test.tsx`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement the three components** (Mission Control tokens only)

`frontend/components/RatingStars.tsx`:

```tsx
/** Compact star meter: filled ★ for the rounded average + numeric value + count. */
export function RatingStars({
  avg,
  count,
}: {
  avg: number | null;
  count: number;
}) {
  if (!count || avg == null) {
    return <span className="font-mono text-[0.65rem] uppercase tracking-wider text-dim">No reviews yet</span>;
  }
  const filled = Math.round(avg);
  return (
    <span className="inline-flex items-center gap-1.5 font-mono text-xs">
      <span aria-hidden className="tracking-tight text-amber">
        {"★".repeat(filled)}
        <span className="text-line-bright">{"★".repeat(5 - filled)}</span>
      </span>
      <span className="tabular font-semibold text-fg">{avg.toFixed(1)}</span>
      <span className="text-dim">({count})</span>
    </span>
  );
}
```

`frontend/components/AgentCard.tsx`:

```tsx
import Link from "next/link";
import type { AgentCardDTO } from "@/lib/types";
import { RatingStars } from "./RatingStars";

/** Marketplace unit card → links to the agent storefront. Builder-private fields never appear. */
export function AgentCard({ agent }: { agent: AgentCardDTO }) {
  return (
    <Link href={`/client/agents/${agent.id}`} className="block h-full">
      <article className="panel panel-hover hud flex h-full flex-col overflow-hidden">
        {/* cover strip */}
        <div className="relative h-20 border-b border-line bg-surface-2">
          {agent.coverUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={agent.coverUrl} alt="" className="size-full object-cover" />
          )}
          {agent.featured && (
            <span className="absolute right-2 top-2 rounded border border-accent/50 bg-accent/15 px-1.5 py-0.5 font-mono text-[0.6rem] font-bold uppercase tracking-wider text-accent">
              🔥 Hot
            </span>
          )}
          <span className="absolute -bottom-4 left-4 grid size-9 place-items-center overflow-hidden rounded-md border border-line-bright bg-base">
            {agent.logoUrl ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={agent.logoUrl} alt="" className="size-full object-cover" />
            ) : (
              <span className="size-3 rounded-[2px] bg-accent" />
            )}
          </span>
        </div>

        <div className="flex flex-1 flex-col gap-2 p-4 pt-6">
          <div>
            <h3 className="truncate text-base font-bold tracking-tight">{agent.name}</h3>
            <p className="font-mono text-[0.65rem] text-dim">by {agent.builderName}</p>
          </div>
          {agent.tagline && <p className="line-clamp-2 text-sm text-muted">{agent.tagline}</p>}
          <div className="flex flex-wrap gap-1.5">
            {agent.categories.map((c) => (
              <span
                key={c}
                className="rounded border border-line bg-surface-2 px-1.5 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-cyan"
              >
                {c}
              </span>
            ))}
          </div>
          <div className="mt-auto flex items-end justify-between border-t border-line pt-3">
            <div className="space-y-1">
              <RatingStars avg={agent.ratingAvg} count={agent.ratingCount} />
              <p className="font-mono text-[0.6rem] uppercase tracking-wider text-dim">
                {agent.requestCount} requests · rep {agent.reputationScore}
              </p>
            </div>
            <p className="tabular font-mono text-sm font-bold text-accent">{agent.price} cr</p>
          </div>
        </div>
      </article>
    </Link>
  );
}
```

`frontend/components/CategoryBar.tsx`:

```tsx
import type { CategoryCountDTO } from "@/lib/types";

/** Horizontal category chips; `active=""` means All. */
export function CategoryBar({
  categories,
  active,
  onSelect,
}: {
  categories: CategoryCountDTO[];
  active: string;
  onSelect: (category: string) => void;
}) {
  const chip = (label: string, value: string, count?: number) => (
    <button
      key={value || "all"}
      type="button"
      onClick={() => onSelect(value)}
      className={`shrink-0 rounded-md border px-3 py-1.5 font-mono text-[0.68rem] uppercase tracking-wider transition ${
        active === value
          ? "border-accent/60 bg-accent/15 text-accent"
          : "border-line bg-surface-2 text-muted hover:border-line-bright hover:text-fg"
      }`}
    >
      {label}
      {count != null && <span className="ml-1.5 text-dim">{count}</span>}
    </button>
  );
  return (
    <div className="flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Browse by category">
      {chip("All", "")}
      {categories.map((c) => chip(c.category, c.category, c.agentCount))}
    </div>
  );
}
```

- [ ] **Step 4: Run — must pass**

Run (in `frontend/`): `npx vitest run components/AgentCard.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add frontend/components/RatingStars.tsx frontend/components/AgentCard.tsx frontend/components/CategoryBar.tsx frontend/components/AgentCard.test.tsx
git commit -m "feat: marketplace AgentCard, RatingStars, CategoryBar components"
```

### Task 9: `/client` becomes the Marketplace; old console moves to `/client/tasks`

**Files:**
- Create: `frontend/app/client/tasks/page.tsx` (receives the ENTIRE current content of `frontend/app/client/page.tsx` — wallet treasury + task list — unchanged except the header link "← console" targets and a "Browse marketplace" CTA)
- Modify: `frontend/app/client/page.tsx` (replaced by the marketplace)
- Modify: `frontend/components/Nav.tsx` (console links)
- Test: `frontend/test/marketplace.test.tsx`, `frontend/test/clientTasks.test.tsx`

- [ ] **Step 1: Move the old client console**

Copy `frontend/app/client/page.tsx` verbatim to `frontend/app/client/tasks/page.tsx` (keep `AppShell` + `RoleGuard role="CLIENT"`; rename the inner component to `ClientTasks`). Update its "+ Submit task" link target — it already points at `/client/tasks/new`, keep it. In `frontend/app/client/tasks/[id]/page.tsx`, change the back link `href="/client"` → `href="/client/tasks"` (label `← my tasks`).

- [ ] **Step 2: Write the failing marketplace test**

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import MarketplacePage from "@/app/client/page";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({}),
  useSearchParams: () => new URLSearchParams(),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderMarketplace() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", role: "CLIENT" }));
  return render(
    <AuthProvider>
      <MarketplacePage />
    </AuthProvider>,
  );
}

describe("marketplace", () => {
  it("lists hot agents with category chips and a storefront link", async () => {
    renderMarketplace();
    expect(await screen.findByText("Summariser Bot")).toBeInTheDocument();
    expect(screen.getByText(/by builder/i)).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /translation/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /summariser bot/i })).toHaveAttribute(
      "href",
      "/client/agents/ag-1",
    );
  });

  it("search narrows results and an empty result state shows", async () => {
    renderMarketplace();
    await screen.findByText("Summariser Bot");
    await userEvent.type(screen.getByPlaceholderText(/search agents/i), "zzz-no-match");
    expect(await screen.findByText(/no agents match/i)).toBeInTheDocument();
  });

  it("category chip filters the grid", async () => {
    renderMarketplace();
    await screen.findByText("Summariser Bot");
    await userEvent.click(await screen.findByRole("button", { name: /translation/i }));
    expect(await screen.findByText(/no agents match/i)).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run — must fail** (old page renders wallet, not the marketplace)

Run (in `frontend/`): `npx vitest run test/marketplace.test.tsx`
Expected: FAIL.

- [ ] **Step 4: Implement the marketplace page** (replace `frontend/app/client/page.tsx`)

```tsx
"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { AgentCard } from "@/components/AgentCard";
import { CategoryBar } from "@/components/CategoryBar";
import { Button, Input } from "@/components/ui";
import type { AgentCardDTO, CatalogueSort, CategoryCountDTO } from "@/lib/types";

const SORTS: { value: CatalogueSort; label: string }[] = [
  { value: "hot", label: "🔥 Hot" },
  { value: "rating", label: "Top rated" },
  { value: "price_asc", label: "Price ↑" },
  { value: "price_desc", label: "Price ↓" },
  { value: "newest", label: "Newest" },
];

function Marketplace() {
  const [agents, setAgents] = useState<AgentCardDTO[] | null>(null);
  const [categories, setCategories] = useState<CategoryCountDTO[]>([]);
  const [q, setQ] = useState("");
  const [category, setCategory] = useState("");
  const [sort, setSort] = useState<CatalogueSort>("hot");
  const [error, setError] = useState<string | null>(null);

  // Categories load once; the grid re-queries on every filter change (debounced search).
  useEffect(() => {
    api<CategoryCountDTO[]>("/catalogue/categories").then(setCategories).catch(() => {});
  }, []);

  useEffect(() => {
    const t = setTimeout(() => {
      const params = new URLSearchParams({ q, category, sort });
      api<AgentCardDTO[]>(`/catalogue/agents?${params}`)
        .then(setAgents)
        .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agents"));
    }, q ? 250 : 0);
    return () => clearTimeout(t);
  }, [q, category, sort]);

  const featured = useMemo(() => (agents ?? []).filter((a) => a.featured), [agents]);

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="eyebrow flex items-center gap-2">
            <span className="inline-block h-px w-6 bg-accent" />
            Marketplace
          </p>
          <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Hire an agent</h1>
          <p className="mt-2 text-sm text-muted">
            Browse verified agents, inspect their output contract, and book directly — escrow
            protects every credit.
          </p>
        </div>
        <Link href="/client/tasks/new">
          <Button variant="secondary">+ Submit open task</Button>
        </Link>
      </header>

      {/* search + sort */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="min-w-64 flex-1">
          <Input
            placeholder="Search agents or builders…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            aria-label="Search agents or builders"
          />
        </div>
        <div className="flex gap-1.5">
          {SORTS.map((s) => (
            <button
              key={s.value}
              type="button"
              onClick={() => setSort(s.value)}
              className={`rounded-md border px-2.5 py-1.5 font-mono text-[0.65rem] uppercase tracking-wider transition ${
                sort === s.value
                  ? "border-accent/60 bg-accent/15 text-accent"
                  : "border-line bg-surface-2 text-muted hover:text-fg"
              }`}
            >
              {s.label}
            </button>
          ))}
        </div>
      </div>

      <CategoryBar categories={categories} active={category} onSelect={setCategory} />

      {error && (
        <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
          {error}
        </p>
      )}

      {/* hot strip (only on unfiltered hot view) */}
      {sort === "hot" && !q && !category && featured.length > 0 && (
        <section aria-label="Hot agents">
          <p className="eyebrow mb-3">🔥 Hot right now</p>
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {featured.map((a) => (
              <AgentCard key={a.id} agent={a} />
            ))}
          </div>
        </section>
      )}

      {/* grid */}
      <section aria-label="All agents">
        <p className="eyebrow mb-3">All agents</p>
        {agents === null ? (
          <p className="font-mono text-sm text-dim">Scanning the registry…</p>
        ) : agents.length === 0 ? (
          <div className="panel p-10 text-center">
            <p className="font-mono text-sm text-muted">No agents match.</p>
            <p className="mt-1 font-mono text-xs text-dim">Try a different search or category.</p>
          </div>
        ) : (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {agents.map((a) => (
              <AgentCard key={a.id} agent={a} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <Marketplace />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 5: Add console nav links** — in `frontend/components/Nav.tsx`, inside the signed-in branch (`role ? (...)`), render role-aware links before the ONLINE chip:

```tsx
{role === "CLIENT" && (
  <div className="hidden items-center gap-1 md:flex">
    {[
      { href: "/client", label: "Marketplace" },
      { href: "/client/tasks", label: "My tasks" },
    ].map((l) => (
      <Link
        key={l.href}
        href={l.href}
        className="rounded-md px-3 py-2 font-mono text-[0.7rem] uppercase tracking-[0.18em] text-muted transition hover:text-fg"
      >
        {l.label}
      </Link>
    ))}
  </div>
)}
```

(Wallet stays inside the My-tasks console — the treasury panel already lives there; a separate `/client/wallet` page is YAGNI for this slice. This is a deliberate refinement of spec §1: nav = `Marketplace · My tasks`.)

- [ ] **Step 6: Write `frontend/test/clientTasks.test.tsx`** — guards the move (the old console must still work at its new path). Mirror the old client-page behaviour: renders wallet balances and the task list from MSW (`GET */api/wallet`, `GET */api/tasks` handlers already exist in `handlers.ts` — check; if `*/api/wallet` / `*/api/tasks` list handlers are missing, add them following the existing `ok(...)` style with one task fixture).

```tsx
// Same harness as marketplace.test.tsx, but importing ClientTasksPage from "@/app/client/tasks/page".
// Asserts: wallet balance text renders; the seeded task title renders; link to /client/tasks/new exists.
import ClientTasksPage from "@/app/client/tasks/page";
// ... render with CLIENT auth seeded ...
// expect(await screen.findByText(/available/i)).toBeInTheDocument();
// expect(await screen.findByRole("link", { name: /submit/i })).toHaveAttribute("href", "/client/tasks/new");
```

Write the full test by mirroring `marketplace.test.tsx`'s harness and asserting on what the moved page actually renders (read the moved file; the treasury labels are "Available"/"In escrow"/"Total").

- [ ] **Step 7: Run all frontend tests + tsc**

Run (in `frontend/`): `npx vitest run` then `npx tsc --noEmit`
Expected: ALL pass — including the pre-existing 22. If `login.test.tsx` fails, it must NOT be edited to pass; the login redirect to `/client` still lands on a CLIENT page, so any failure indicates a real break — fix the page.

- [ ] **Step 8: Commit**

```bash
git add frontend/app/client frontend/components/Nav.tsx frontend/test/marketplace.test.tsx frontend/test/clientTasks.test.tsx
git commit -m "feat: marketplace home with search/category/hot strip; move client console to /client/tasks"
```

### Task 10: Agent storefront page `/client/agents/[id]` (TDD)

**Files:**
- Create: `frontend/app/client/agents/[id]/page.tsx`
- Create: `frontend/components/ReviewList.tsx`
- Test: `frontend/test/storefront.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// Harness as marketplace.test.tsx, plus params mock for the dynamic segment:
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "ag-1" }),
  useSearchParams: () => new URLSearchParams(),
}));
import StorefrontPage from "@/app/client/agents/[id]/page";

describe("agent storefront", () => {
  it("renders profile content, output contract, stats, reviews and the book CTA", async () => {
    renderWithClientAuth(<StorefrontPage />);
    expect(await screen.findByText("Summariser Bot")).toBeInTheDocument();
    expect(screen.getByText(/summarises long documents/i)).toBeInTheDocument();
    expect(screen.getByText(/example output/i)).toBeInTheDocument(); // sample output block
    expect(screen.getByText(/valid json/i)).toBeInTheDocument(); // output contract
    expect(screen.getByText(/output matched the spec exactly/i)).toBeInTheDocument(); // review
    expect(screen.getByRole("link", { name: /book this agent/i })).toHaveAttribute(
      "href",
      "/client/agents/ag-1/book",
    );
  });

  it("shows an error state for an unknown agent", async () => {
    // override the params mock per-test is awkward with vi.mock — instead drive via MSW:
    // navigate is fixed to ag-1 here, so this case lives in booking.test or uses a second
    // describe file with its own vi.mock returning id "ag-404".
  });
});
```

> Implementation note for the executor: put the unknown-agent case in its own test FILE (`storefront404.test.tsx`) with `useParams: () => ({ id: "ag-404" })`, asserting `await screen.findByRole("alert")` contains "Agent not found". `vi.mock` is per-module, so a separate file is the clean way to vary the route param.

- [ ] **Step 2: Run — must fail.** `npx vitest run test/storefront.test.tsx` → module not found.

- [ ] **Step 3: Implement `ReviewList` + the page**

`frontend/components/ReviewList.tsx`:

```tsx
import type { CatalogueReviewDTO } from "@/lib/types";
import { RatingStars } from "./RatingStars";

export function ReviewList({ reviews }: { reviews: CatalogueReviewDTO[] }) {
  if (reviews.length === 0) {
    return <p className="font-mono text-xs text-dim">No reviews yet.</p>;
  }
  return (
    <ul className="space-y-3">
      {reviews.map((r) => (
        <li key={r.id} className="rounded-md border border-line bg-surface-2 p-4">
          <div className="flex items-center justify-between gap-3">
            <RatingStars avg={r.rating} count={1} />
            <p className="font-mono text-[0.65rem] text-dim">
              {r.author} · {new Date(r.createdAt).toLocaleDateString()}
            </p>
          </div>
          {r.reviewText && <p className="mt-2 text-sm text-muted">{r.reviewText}</p>}
          {r.builderResponse && (
            <div className="mt-3 border-l-2 border-accent/40 pl-3">
              <p className="font-mono text-[0.6rem] uppercase tracking-wider text-accent">
                Builder response
              </p>
              <p className="mt-1 text-sm text-muted">{r.builderResponse}</p>
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}
```

`frontend/app/client/agents/[id]/page.tsx` — structure (matches the approved mock: hero cover + floating logo, title row with rating, builder-controlled green sections, contract + stats + reviews, sticky book sidebar):

```tsx
"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { RoleGuard } from "@/components/RoleGuard";
import { AppShell } from "@/components/AppShell";
import { RatingStars } from "@/components/RatingStars";
import { ReviewList } from "@/components/ReviewList";
import { Button } from "@/components/ui";
import type { AgentProfileDTO } from "@/lib/types";

function Storefront() {
  const { id } = useParams<{ id: string }>();
  const [profile, setProfile] = useState<AgentProfileDTO | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    api<AgentProfileDTO>(`/catalogue/agents/${id}`)
      .then(setProfile)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Failed to load agent"));
  }, [id]);

  if (error)
    return (
      <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
        {error}
      </p>
    );
  if (!profile) return <p className="font-mono text-sm text-dim">Loading storefront…</p>;

  const { card, stats } = profile;

  return (
    <div className="space-y-6">
      <Link href="/client" className="font-mono text-xs text-dim transition hover:text-accent">
        ← marketplace
      </Link>

      {/* hero */}
      <div className="relative h-40 overflow-hidden rounded-xl border border-line bg-surface-2">
        {card.coverUrl && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={card.coverUrl} alt="" className="size-full object-cover" />
        )}
        <span className="absolute -bottom-0 left-6 grid size-16 translate-y-1/3 place-items-center overflow-hidden rounded-lg border-2 border-accent/50 bg-base">
          {card.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={card.logoUrl} alt="" className="size-full object-cover" />
          ) : (
            <span className="size-5 rounded-[3px] bg-accent" />
          )}
        </span>
      </div>

      <div className="grid gap-6 lg:grid-cols-[1fr_260px]">
        <div className="space-y-6 pt-4">
          <header className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="text-2xl font-extrabold tracking-tight">{card.name}</h1>
              <p className="mt-1 font-mono text-xs text-dim">
                by {card.builderName} · rep <span className="tabular">{card.reputationScore}</span>
              </p>
            </div>
            <RatingStars avg={card.ratingAvg} count={card.ratingCount} />
          </header>

          {card.tagline && <p className="text-lg text-fg">{card.tagline}</p>}

          {profile.description && (
            <section>
              <p className="eyebrow mb-2">What this agent does</p>
              <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted">{profile.description}</p>
            </section>
          )}

          {profile.sampleOutput && (
            <section>
              <p className="eyebrow mb-2">Sample output</p>
              <pre className="overflow-auto rounded-md border border-line bg-base p-4 font-mono text-xs leading-relaxed text-fg">
                {profile.sampleOutput}
              </pre>
            </section>
          )}

          {profile.galleryUrls.length > 0 && (
            <section>
              <p className="eyebrow mb-2">Gallery</p>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                {profile.galleryUrls.map((u) => (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img key={u} src={u} alt="" className="h-24 w-full rounded-md border border-line object-cover" />
                ))}
              </div>
            </section>
          )}

          <section>
            <p className="eyebrow mb-2">Output contract</p>
            <div className="rounded-md border border-line bg-surface-2 p-4 font-mono text-xs text-muted">
              <p>
                format ▸ <span className="text-cyan">{profile.outputSpec.format}</span>
              </p>
              {profile.outputSpec.schema && <p className="mt-1">schema ▸ {profile.outputSpec.schema}</p>}
              {profile.outputSpec.acceptanceCriteria && (
                <p className="mt-1">accepts ▸ {profile.outputSpec.acceptanceCriteria}</p>
              )}
            </div>
          </section>

          <section>
            <p className="eyebrow mb-2">Track record</p>
            <div className="grid grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
              {[
                { v: stats.requestCount, l: "requests" },
                { v: stats.successRate == null ? "—" : `${Math.round(stats.successRate * 100)}%`, l: "success" },
                {
                  v: stats.avgTurnaroundSeconds == null ? "—" : `${Math.round(stats.avgTurnaroundSeconds)}s`,
                  l: "avg turnaround",
                },
              ].map((s) => (
                <div key={s.l} className="bg-surface px-4 py-4">
                  <p className="tabular text-xl font-extrabold text-fg">{s.v}</p>
                  <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">{s.l}</p>
                </div>
              ))}
            </div>
          </section>

          <section>
            <p className="eyebrow mb-2">Reviews</p>
            <ReviewList reviews={profile.reviews} />
            <p className="mt-2 font-mono text-[0.6rem] uppercase tracking-wider text-dim">
              Demo ratings — review submission opens with validation &amp; settlement (Module 4/5).
            </p>
          </section>
        </div>

        {/* sticky booking sidebar */}
        <aside className="lg:sticky lg:top-24 lg:self-start">
          <div className="panel hud space-y-4 p-5">
            <div>
              <p className="eyebrow">Price</p>
              <p className="tabular mt-1 text-3xl font-extrabold text-accent">
                {card.price} <span className="text-sm font-semibold text-muted">cr</span>
              </p>
            </div>
            <p className="font-mono text-xs text-muted">
              ≤ <span className="tabular">{card.maxExecutionSeconds}s</span> execution · escrow-protected
            </p>
            <div className="flex flex-wrap gap-1.5">
              {card.categories.map((c) => (
                <span key={c} className="rounded border border-line bg-surface-2 px-1.5 py-0.5 font-mono text-[0.6rem] uppercase tracking-wider text-cyan">
                  {c}
                </span>
              ))}
            </div>
            <Link href={`/client/agents/${card.id}/book`} className="block">
              <Button className="w-full">Book this agent ▸</Button>
            </Link>
            <p className="font-mono text-[0.6rem] leading-relaxed text-dim">
              You accept this agent's output contract; credits freeze in escrow on submit.
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}

export default function Page() {
  return (
    <AppShell>
      <RoleGuard role="CLIENT">
        <Storefront />
      </RoleGuard>
    </AppShell>
  );
}
```

- [ ] **Step 4: Run — must pass.** `npx vitest run test/storefront.test.tsx` (+ the 404 sibling file). Then full `npx vitest run` + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/client/agents frontend/components/ReviewList.tsx frontend/test/storefront*.test.tsx
git commit -m "feat: agent storefront page with contract, stats, reviews, book CTA"
```

# Phase 4 — Direct booking

> **Spec refinement (instead of §4.3's optional `agentId` on POST /api/tasks):** direct booking gets its own endpoint `POST /api/tasks/direct` with its own request record. Reason: the auto-route path keeps `@NotBlank category` / `@NotNull outputSpec` Bean Validation intact instead of weakening them to nullable + manual checks. Everything else in §4.3 holds (escrow-first, adopt agent spec, skip matcher, reuse dispatch).

### Task 11: Backend direct booking (TDD)

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/task/event/TaskSubmittedDomainEvent.java` — add nullable `UUID directAgentVersionId` as the LAST component; update the existing publish site in `TaskWriteAppServiceImpl.submit` to pass `null`, and fix any test constructors.
- Modify: `backend/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java` + `impl/TaskWriteAppServiceImpl.java` — add `UUID submitDirectlyBooked(TaskSubmitInfo info, UUID agentVersionId)`; extract shared private `doSubmit(info, directAgentVersionId)` so escrow-freeze atomicity (Invariant #1) is written once.
- Modify: `backend/src/main/java/com/hireai/application/biz/routing/RoutingEventListener.java` — branch on the event's target.
- Modify: `backend/src/main/java/com/hireai/application/biz/routing/RoutingAppService.java` + `impl/RoutingAppServiceImpl.java` — add `void dispatchDirect(UUID taskId, UUID agentVersionId)` (reuses the private `buildDispatchMessage`).
- Modify: `backend/src/main/java/com/hireai/domain/biz/agent/repository/AgentRepository.java` + `AgentRepositoryImpl.java` + `AgentVersionJpaRepository.java` — add `Optional<AgentCandidate> findCandidateByVersionId(UUID agentVersionId)`. Mirror the EXISTING `findActiveCandidates` native query/projection in `AgentVersionJpaRepository` (read it first), changing the WHERE to `v.id = :versionId AND a.status = 'ACTIVE'` (no category/price filter), same projection columns.
- Create: `backend/src/main/java/com/hireai/application/biz/task/DirectBookingAppService.java` + `impl/DirectBookingAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/task/dto/DirectBookRequest.java`; Modify: `TaskController.java`
- Tests: `backend/src/test/java/com/hireai/task/DirectBookingAppServiceTest.java` (unit), additions to `backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java` (slice), `backend/src/test/java/com/hireai/routing/RoutingEventListenerTest.java` (unit — create if absent), `backend/src/test/java/com/hireai/task/DirectBookingIntegrationTest.java`

- [ ] **Step 1: Write the failing unit test for the booking orchestration**

```java
package com.hireai.task;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.impl.DirectBookingAppServiceImpl;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.wallet.model.Money;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DirectBookingAppServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentProfileRepository profileRepository = mock(AgentProfileRepository.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);
    private final DirectBookingAppServiceImpl service =
            new DirectBookingAppServiceImpl(agentRepository, profileRepository, taskWriteAppService);

    /** NOTE: check OutputFormat's real package before running (task vs agent enums). */
    private AgentModel activeAgent(UUID agentId, String price) {
        UUID versionId = UUID.randomUUID();
        AgentVersionModel version = new AgentVersionModel(versionId, agentId, 1,
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), "https://agent.example/run", 60,
                Pricing.of(new BigDecimal(price)), Instant.now());
        return new AgentModel(agentId, UUID.randomUUID(), "Summariser", AgentStatus.ACTIVE,
                versionId, new BigDecimal("60.00"), version, Instant.now());
    }

    private void listed(UUID agentId, boolean listed) {
        AgentProfileModel profile = AgentProfileModel.createDefault(agentId)
                .updateContent(null, null, null, listed);
        when(profileRepository.findByAgentId(agentId)).thenReturn(Optional.of(profile));
    }

    @Test
    void booksAdoptingAgentSpecAndFirstCategory() {
        UUID agentId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        AgentModel agent = activeAgent(agentId, "10.00");
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        listed(agentId, true);
        UUID taskId = UUID.randomUUID();
        when(taskWriteAppService.submitDirectlyBooked(any(), any())).thenReturn(taskId);

        UUID result = service.book(new DirectBookingInfo(
                clientId, "Summarise Q2", "Summarise the report", Money.of("15.00"), agentId));

        assertThat(result).isEqualTo(taskId);
        ArgumentCaptor<TaskSubmitInfo> info = ArgumentCaptor.forClass(TaskSubmitInfo.class);
        verify(taskWriteAppService).submitDirectlyBooked(info.capture(), eq(agent.currentVersionId()));
        assertThat(info.getValue().outputSpec()).isEqualTo(agent.currentVersion().outputSpec());
        assertThat(info.getValue().category()).isEqualTo("summarisation");
        assertThat(info.getValue().clientId()).isEqualTo(clientId);
    }

    @Test
    void rejectsBudgetBelowPrice() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(activeAgent(agentId, "50.00")));
        listed(agentId, true);

        assertThatThrownBy(() -> service.book(new DirectBookingInfo(
                UUID.randomUUID(), "t", "d", Money.of("10.00"), agentId)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode())
                .isEqualTo(ResultCode.VALIDATION_ERROR);
        verifyNoInteractions(taskWriteAppService);
    }

    @Test
    void rejectsUnlistedOrUnknownAgentAsNotFound() {
        UUID unlisted = UUID.randomUUID();
        when(agentRepository.findById(unlisted)).thenReturn(Optional.of(activeAgent(unlisted, "5.00")));
        listed(unlisted, false);
        assertThatThrownBy(() -> service.book(new DirectBookingInfo(
                UUID.randomUUID(), "t", "d", Money.of("10.00"), unlisted)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);

        UUID unknown = UUID.randomUUID();
        when(agentRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.book(new DirectBookingInfo(
                UUID.randomUUID(), "t", "d", Money.of("10.00"), unknown)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
    }
}
```

- [ ] **Step 2: Run — must fail to compile.** `mvn -f backend/pom.xml -B test -Dtest=DirectBookingAppServiceTest`

- [ ] **Step 3: Implement the domain carrier, app service, event/listener/routing changes**

`domain/biz/task/info/DirectBookingInfo.java`:

```java
package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.wallet.model.Money;

import java.util.UUID;

/** Carrier for a direct booking: the client targets ONE agent; the spec is adopted from it. */
public record DirectBookingInfo(UUID clientId, String title, String description,
                                Money budget, UUID agentId) {
}
```

`application/biz/task/DirectBookingAppService.java`:

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.DirectBookingInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Direct booking (spec §4.3): client hires a SPECIFIC agent. Validates the target is ACTIVE +
 * listed and budget >= price, adopts the agent's output_spec as the binding contract
 * (Invariant #4), then submits with escrow freeze (Invariant #1) and a pinned dispatch that
 * SKIPS matching. Settlement still owns escrow exit (Invariant #1, unchanged).
 */
@Validated
public interface DirectBookingAppService {

    UUID book(@NonNull DirectBookingInfo info);
}
```

`application/biz/task/impl/DirectBookingAppServiceImpl.java`:

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DirectBookingAppServiceImpl implements DirectBookingAppService {

    private final AgentRepository agentRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final TaskWriteAppService taskWriteAppService;

    @Override
    public UUID book(DirectBookingInfo info) {
        AgentModel agent = agentRepository.findById(info.agentId())
                .orElseThrow(this::notFound);
        // Bookable = ACTIVE + listed. Both failures read as NOT_FOUND so unlisted agents
        // are indistinguishable from absent ones (no existence leak — spec §6).
        if (agent.status() != AgentStatus.ACTIVE || !isListed(agent.id())) {
            throw notFound();
        }
        if (info.budget().value().compareTo(agent.currentVersion().pricing().price()) < 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Budget " + info.budget() + " is below the agent's price "
                            + agent.currentVersion().pricing().price());
        }
        // Adopt the agent's declared output_spec as the task's binding contract (Invariant #4)
        // and its first capability category as the task category (stats/labels).
        TaskSubmitInfo submitInfo = new TaskSubmitInfo(
                info.clientId(), info.title(), info.description(), info.budget(),
                agent.currentVersion().outputSpec(),
                agent.currentVersion().capabilityCategories().get(0));
        UUID taskId = taskWriteAppService.submitDirectlyBooked(submitInfo, agent.currentVersionId());
        log.info("Task {} direct-booked to agent {} (version {})",
                taskId, agent.id(), agent.currentVersionId());
        return taskId;
    }

    private boolean isListed(UUID agentId) {
        return agentProfileRepository.findByAgentId(agentId)
                .map(p -> p.listed())
                .orElse(false);
    }

    private DomainException notFound() {
        return new DomainException(ResultCode.NOT_FOUND, "Agent not found");
    }
}
```

> `Money.value()` — verify the accessor name on `domain/biz/wallet/model/Money` (it may be `amount()`/`value()`); adjust the comparison accordingly. If `Money` has `isGreaterThan`, prefer `Pricing`-side comparison with that.

Event — `TaskSubmittedDomainEvent` becomes:

```java
public record TaskSubmittedDomainEvent(UUID taskId, UUID clientId, Money budget,
                                       Instant occurredAt, UUID directAgentVersionId) {
}
```

`TaskWriteAppService` — add to the interface:

```java
/** Direct booking: identical atomic submit+freeze, but routing is pinned to agentVersionId. */
UUID submitDirectlyBooked(@NonNull TaskSubmitInfo taskSubmitInfo, @NonNull UUID agentVersionId);
```

`TaskWriteAppServiceImpl` — refactor `submit` and add the new method (escrow atomicity written ONCE):

```java
@Override
public UUID submit(TaskSubmitInfo taskSubmitInfo) {
    return doSubmit(taskSubmitInfo, null);
}

@Override
public UUID submitDirectlyBooked(TaskSubmitInfo taskSubmitInfo, UUID agentVersionId) {
    return doSubmit(taskSubmitInfo, agentVersionId);
}

private UUID doSubmit(TaskSubmitInfo taskSubmitInfo, UUID directAgentVersionId) {
    String correlationId = UUID.randomUUID().toString();
    TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo);
    UUID taskId = taskRepository.save(task).id();
    walletWriteAppService.freeze(taskSubmitInfo.clientId(), taskSubmitInfo.budget(), taskId, correlationId);
    eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
            taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), task.createdAt(),
            directAgentVersionId));
    log.info("Task {} submitted by client {}; budget {} frozen in escrow{}",
            taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(),
            directAgentVersionId == null ? "" : " (direct-booked to version " + directAgentVersionId + ")");
    return taskId;
}
```

`RoutingEventListener`:

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onTaskSubmitted(TaskSubmittedDomainEvent event) {
    if (event.directAgentVersionId() != null) {
        log.info("Task {} submit committed; dispatching directly to version {}",
                event.taskId(), event.directAgentVersionId());
        routingAppService.dispatchDirect(event.taskId(), event.directAgentVersionId());
        return;
    }
    log.info("Task {} submit committed; starting routing", event.taskId());
    routingAppService.route(event.taskId());
}
```

`RoutingAppService` — add `void dispatchDirect(UUID taskId, UUID agentVersionId);` and in `RoutingAppServiceImpl`:

```java
@Override
public void dispatchDirect(UUID taskId, UUID agentVersionId) {
    TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
    AgentCandidate target = agentRepository.findCandidateByVersionId(agentVersionId)
            .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                    "Active agent version not found: " + agentVersionId));
    // Same ordering contract as route(): QUEUED commits FIRST (REQUIRES_NEW), then publish.
    taskWriteAppService.assignAndQueue(taskId, agentVersionId);
    DispatchMessage message = buildDispatchMessage(taskId, agentVersionId, view, target);
    taskDispatchPublisher.publish(message);
    log.info("Task {} direct-dispatched to agentVersion {} (correlationId={})",
            taskId, agentVersionId, message.correlationId());
}
```

`AgentRepository` — add:

```java
/** The dispatch view of ONE specific version, present only while its agent is ACTIVE. */
Optional<AgentCandidate> findCandidateByVersionId(UUID agentVersionId);
```

`AgentRepositoryImpl` — map exactly like `findActiveCandidates` (same row→`AgentCandidate` mapping) over a new `versionJpa.findCandidateByVersionId(versionId)` native query (copy the existing `findActiveCandidates` query in `AgentVersionJpaRepository`, WHERE `v.id = :versionId AND a.status = 'ACTIVE'`).

- [ ] **Step 4: Controller + request DTO**

`controller/biz/task/dto/DirectBookRequest.java`:

```java
package com.hireai.controller.biz.task.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public record DirectBookRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull UUID agentId
) {
}
```

`TaskController` — inject `DirectBookingAppService directBookingAppService` and add:

```java
@PostMapping("/direct")
public WebResult<TaskDTO> bookDirect(@Valid @RequestBody DirectBookRequest request) {
    UUID clientId = currentUser.currentUserId();
    UUID taskId = directBookingAppService.book(new DirectBookingInfo(
            clientId, request.title(), request.description(),
            Money.of(request.budget()), request.agentId()));
    return ok(TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId)));
}
```

Slice tests to append to `TaskControllerTest` (add `@MockBean DirectBookingAppService directBookingAppService`): happy path returns the created task DTO; service throwing `VALIDATION_ERROR` ("budget below") → 400; unknown agent `NOT_FOUND` → 404; missing `agentId` → 400 (Bean Validation).

Listener unit test (`routing/RoutingEventListenerTest.java`): mock `RoutingAppService`; event with `directAgentVersionId=null` → `verify(route)`, with a UUID → `verify(dispatchDirect)` and `verify(routingAppService, never()).route(any())`.

- [ ] **Step 5: Run unit + slice tests — must pass**

Run: `mvn -f backend/pom.xml -B test -Dtest="DirectBookingAppServiceTest,TaskControllerTest,RoutingEventListenerTest"`
Expected: PASS.

- [ ] **Step 6: Write + run the integration test**

`task/DirectBookingIntegrationTest.java` — `@SpringBootTest @Testcontainers @ActiveProfiles("test") @EnabledIf("dockerAvailable")`, `@MockBean TaskDispatchPublisher taskDispatchPublisher` (no RabbitMQ container needed). Flow: seed a funded client (insert user + wallet rows via `JdbcTemplate`, available 100.00), register+activate an agent via `AgentWriteAppService` (HTTPS webhook), mark its profile listed via `AgentProfileRepository`, then `directBookingAppService.book(...)` with budget 20.00. Assert:
- task row: status `QUEUED`, `agent_version_id` = the agent's current version, `output_spec` equals the AGENT's spec (query via `TaskRepository.findById`),
- wallet: available 80.00 / escrow 20.00 (Invariant #1),
- `verify(taskDispatchPublisher).publish(captor)` → message `agentVersionId` matches and `payload.outputSpecJson()` is the agent's contract,
- booking with budget 5.00 (below price) throws and leaves NO task row and an UNCHANGED wallet.

Run: `mvn -f backend/pom.xml -B test -Dtest=DirectBookingIntegrationTest` → PASS.

- [ ] **Step 7: Full backend suite green, then commit**

```bash
mvn -f backend/pom.xml -B test
git add backend/src/main backend/src/test
git commit -m "feat: direct booking - pinned dispatch that adopts the agent's output contract"
```

### Task 12: Frontend booking page (TDD)

**Files:**
- Create: `frontend/app/client/agents/[id]/book/page.tsx`
- Test: `frontend/test/booking.test.tsx`

- [ ] **Step 1: Write the failing test**

```tsx
// Harness as storefront.test.tsx (useParams → { id: "ag-1" }); capture push from useRouter mock.
import BookingPage from "@/app/client/agents/[id]/book/page";

describe("direct booking", () => {
  it("prefills budget with the agent price, shows the adopted contract read-only, books, redirects", async () => {
    renderWithClientAuth(<BookingPage />);
    expect(await screen.findByText(/summariser bot/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/budget/i)).toHaveValue(10); // prefilled = price
    expect(screen.getByText(/valid json/i)).toBeInTheDocument(); // adopted contract, read-only
    expect(screen.queryByLabelText(/category/i)).not.toBeInTheDocument(); // no category/spec inputs

    await userEvent.type(screen.getByLabelText(/title/i), "Summarise Q2");
    await userEvent.type(screen.getByLabelText(/description/i), "Summarise the Q2 report");
    await userEvent.click(screen.getByRole("button", { name: /book/i }));
    await vi.waitFor(() => expect(push).toHaveBeenCalledWith("/client/tasks/t-direct-1"));
  });

  it("surfaces a budget-below-price rejection", async () => {
    renderWithClientAuth(<BookingPage />);
    await screen.findByText(/summariser bot/i);
    await userEvent.type(screen.getByLabelText(/title/i), "T");
    await userEvent.type(screen.getByLabelText(/description/i), "D");
    await userEvent.clear(screen.getByLabelText(/budget/i));
    await userEvent.type(screen.getByLabelText(/budget/i), "5");
    await userEvent.click(screen.getByRole("button", { name: /book/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/below agent price/i);
  });
});
```

- [ ] **Step 2: Run — must fail.** `npx vitest run test/booking.test.tsx`

- [ ] **Step 3: Implement the page** — loads the profile for price/contract; form = title + description + budget (number, min = price, default = price); shows the adopted `outputSpec` in a read-only contract panel (reuse the storefront's contract block); submit `api<TaskDTO>("/tasks/direct", { method: "POST", body: JSON.stringify({ title, description, budget, agentId: id }) })` → `router.push(`/client/tasks/${created.id}`)`; `ApiError` message into a `role="alert"` box. Structure mirrors `client/tasks/new/page.tsx` (form state, submitting flag, `AppShell` + `RoleGuard role="CLIENT"`); back-link `← storefront` → `/client/agents/${id}`. Submit button label: `Book & freeze escrow ▸`. Include a one-line note: "You accept this agent's declared output contract."

- [ ] **Step 4: Run — must pass.** `npx vitest run test/booking.test.tsx`, then full `npx vitest run` + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/client/agents frontend/test/booking.test.tsx
git commit -m "feat: direct-booking form adopting the agent's output contract"
```

---

# Phase 5 — Builder storefront management & stats

### Task 13: Backend — storefront app service + profile/media/review endpoints (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/agent/info/ProfileUpdateInfo.java`
- Create: `backend/src/main/java/com/hireai/application/biz/agent/AgentStorefrontAppService.java` + `impl/AgentStorefrontAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/dto/AgentProfileViewDTO.java`, `UpdateProfileRequest.java`, `ReviewDTO.java`, `RespondReviewRequest.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/agent/AgentController.java`
- Tests: `backend/src/test/java/com/hireai/agent/AgentStorefrontAppServiceTest.java` (unit), `backend/src/test/java/com/hireai/controller/biz/agent/AgentControllerStorefrontTest.java` (slice)

- [ ] **Step 1: Write the failing app-service unit test** — mocks `AgentReadAppService` (owner gate), `AgentProfileRepository`, `MediaStoragePort`, `ReviewRepository`. Cases:
  - `updateProfile` happy: owner gate consulted (`verify(agentReadAppService).getForOwner(agentId, ownerId)`), repository saves model with new tagline/listing.
  - `updateProfile` foreign agent: `getForOwner` throws NOT_FOUND → propagates, repository untouched.
  - `uploadMedia` happy (kind=logo, image/png, 1KB): port called with key matching regex `agents/<agentId>/logo-[0-9a-f-]+\.png`, profile saved with returned URL.
  - `uploadMedia` rejects content type `application/pdf` → `VALIDATION_ERROR`; rejects size > 2_097_152 → `VALIDATION_ERROR`; port NEVER called.
  - `uploadMedia` gallery at MAX_GALLERY → `DOMAIN_RULE_VIOLATION` (from the model), port not called (check BEFORE upload by reading the profile first).
  - `respondToReview` happy: saves `review.respond(text)`; review belonging to a DIFFERENT agent → `NOT_FOUND`.

Write these as plain-Mockito tests in the style of `DirectBookingAppServiceTest` (Step 1 of Task 11) — same arrange/act/assert layout, `ArgumentCaptor` on the repository saves.

- [ ] **Step 2: Run — must fail to compile.** `mvn -f backend/pom.xml -B test -Dtest=AgentStorefrontAppServiceTest`

- [ ] **Step 3: Implement**

`domain/biz/agent/info/ProfileUpdateInfo.java`:

```java
package com.hireai.domain.biz.agent.info;

public record ProfileUpdateInfo(String tagline, String description, String sampleOutput,
                                boolean listed) {
}
```

`AgentStorefrontAppService.java`:

```java
package com.hireai.application.biz.agent;

import com.hireai.domain.biz.agent.info.ProfileUpdateInfo;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.review.model.ReviewModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Builder-side storefront management. EVERY method re-derives ownership through
 * AgentReadAppService.getForOwner (Invariant #5) before touching profile, media, or reviews.
 */
@Validated
public interface AgentStorefrontAppService {

    AgentProfileModel getProfile(@NonNull UUID agentId, @NonNull UUID ownerId);

    AgentProfileModel updateProfile(@NonNull UUID agentId, @NonNull UUID ownerId,
                                    @NonNull ProfileUpdateInfo info);

    AgentProfileModel uploadMedia(@NonNull UUID agentId, @NonNull UUID ownerId,
                                  @NonNull String kind, @NonNull String contentType,
                                  long sizeBytes, byte @NonNull [] bytes);

    AgentProfileModel removeMedia(@NonNull UUID agentId, @NonNull UUID ownerId,
                                  @NonNull String kind, @NonNull String url);

    List<ReviewModel> reviews(@NonNull UUID agentId, @NonNull UUID ownerId);

    ReviewModel respondToReview(@NonNull UUID agentId, @NonNull UUID ownerId,
                                @NonNull UUID reviewId, @NonNull String response);
}
```

`impl/AgentStorefrontAppServiceImpl.java` (key logic — full file):

```java
package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentStorefrontAppService;
import com.hireai.application.port.storage.MediaStoragePort;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.ProfileUpdateInfo;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.review.model.ReviewModel;
import com.hireai.domain.biz.review.repository.ReviewRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentStorefrontAppServiceImpl implements AgentStorefrontAppService {

    private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");
    private static final List<String> KINDS = List.of("logo", "cover", "gallery");
    private static final int REVIEWS_LIMIT = 50;

    private final AgentReadAppService agentReadAppService;
    private final AgentProfileRepository profileRepository;
    private final MediaStoragePort mediaStoragePort;
    private final ReviewRepository reviewRepository;

    @Override
    @Transactional(readOnly = true)
    public AgentProfileModel getProfile(UUID agentId, UUID ownerId) {
        agentReadAppService.getForOwner(agentId, ownerId); // throws NOT_FOUND when not owner
        return loadProfile(agentId);
    }

    @Override
    public AgentProfileModel updateProfile(UUID agentId, UUID ownerId, ProfileUpdateInfo info) {
        agentReadAppService.getForOwner(agentId, ownerId);
        AgentProfileModel updated = loadProfile(agentId)
                .updateContent(info.tagline(), info.description(), info.sampleOutput(), info.listed());
        return profileRepository.save(updated);
    }

    @Override
    public AgentProfileModel uploadMedia(UUID agentId, UUID ownerId, String kind,
                                         String contentType, long sizeBytes, byte[] bytes) {
        agentReadAppService.getForOwner(agentId, ownerId);
        if (!KINDS.contains(kind)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Unknown media kind: " + kind);
        }
        String ext = ALLOWED_TYPES.get(contentType);
        if (ext == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unsupported image type (allowed: png, jpeg, webp)");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_IMAGE_BYTES) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Image must be 1B-2MB");
        }
        AgentProfileModel profile = loadProfile(agentId);
        if ("gallery".equals(kind) && profile.galleryUrls().size() >= AgentProfileModel.MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + AgentProfileModel.MAX_GALLERY + " images)");
        }
        String objectKey = "agents/" + agentId + "/" + kind + "-" + UUID.randomUUID() + "." + ext;
        String url = mediaStoragePort.upload(objectKey, contentType, bytes);
        AgentProfileModel updated = switch (kind) {
            case "logo" -> profile.withLogo(url);
            case "cover" -> profile.withCover(url);
            default -> profile.addGalleryUrl(url);
        };
        return profileRepository.save(updated);
    }

    @Override
    public AgentProfileModel removeMedia(UUID agentId, UUID ownerId, String kind, String url) {
        agentReadAppService.getForOwner(agentId, ownerId);
        AgentProfileModel updated = loadProfile(agentId).removeMedia(kind, url);
        mediaStoragePort.deleteByUrl(url); // best-effort; storage drift is acceptable
        return profileRepository.save(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewModel> reviews(UUID agentId, UUID ownerId) {
        agentReadAppService.getForOwner(agentId, ownerId);
        return reviewRepository.findPublishedByAgentId(agentId, REVIEWS_LIMIT);
    }

    @Override
    public ReviewModel respondToReview(UUID agentId, UUID ownerId, UUID reviewId, String response) {
        agentReadAppService.getForOwner(agentId, ownerId);
        ReviewModel review = reviewRepository.findById(reviewId)
                .filter(r -> r.agentId().equals(agentId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Review not found: " + reviewId));
        return reviewRepository.save(review.respond(response));
    }

    private AgentProfileModel loadProfile(UUID agentId) {
        return profileRepository.findByAgentId(agentId)
                .orElseGet(() -> AgentProfileModel.createDefault(agentId)); // pre-V6 agents
    }
}
```

- [ ] **Step 4: Run the unit test — must pass.** `mvn -f backend/pom.xml -B test -Dtest=AgentStorefrontAppServiceTest`

- [ ] **Step 5: Controller endpoints + slice test**

DTOs (records in `controller/biz/agent/dto/`):

```java
public record AgentProfileViewDTO(String tagline, String description, String sampleOutput,
                                  String logoUrl, String coverUrl, java.util.List<String> galleryUrls,
                                  boolean listed, boolean featured) {
    public static AgentProfileViewDTO from(com.hireai.domain.biz.agent.model.AgentProfileModel p) {
        return new AgentProfileViewDTO(p.tagline(), p.description(), p.sampleOutput(),
                p.logoUrl(), p.coverUrl(), p.galleryUrls(), p.listed(), p.featured());
    }
}

public record UpdateProfileRequest(@jakarta.validation.constraints.Size(max = 160) String tagline,
                                   @jakarta.validation.constraints.Size(max = 8000) String description,
                                   @jakarta.validation.constraints.Size(max = 8000) String sampleOutput,
                                   boolean isListed) {
}

public record ReviewDTO(java.util.UUID id, int rating, String reviewText, String builderResponse,
                        java.time.Instant createdAt) {
    public static ReviewDTO from(com.hireai.domain.biz.review.model.ReviewModel r) {
        return new ReviewDTO(r.id(), r.rating(), r.reviewText(), r.builderResponse(), r.createdAt());
    }
}

public record RespondReviewRequest(@jakarta.validation.constraints.NotBlank
                                   @jakarta.validation.constraints.Size(max = 2000) String response) {
}
```

`AgentController` additions (inject `AgentStorefrontAppService storefrontAppService`):

```java
@GetMapping("/{agentId}/profile")
public WebResult<AgentProfileViewDTO> getProfile(@PathVariable("agentId") UUID agentId) {
    return ok(AgentProfileViewDTO.from(
            storefrontAppService.getProfile(agentId, currentUser.currentUserId())));
}

@PutMapping("/{agentId}/profile")
public WebResult<AgentProfileViewDTO> updateProfile(@PathVariable("agentId") UUID agentId,
                                                    @Valid @RequestBody UpdateProfileRequest request) {
    return ok(AgentProfileViewDTO.from(storefrontAppService.updateProfile(
            agentId, currentUser.currentUserId(),
            new ProfileUpdateInfo(request.tagline(), request.description(),
                    request.sampleOutput(), request.isListed()))));
}

@PostMapping(value = "/{agentId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public WebResult<AgentProfileViewDTO> uploadMedia(@PathVariable("agentId") UUID agentId,
                                                  @RequestParam("kind") String kind,
                                                  @RequestPart("file") MultipartFile file)
        throws java.io.IOException {
    return ok(AgentProfileViewDTO.from(storefrontAppService.uploadMedia(
            agentId, currentUser.currentUserId(), kind,
            file.getContentType() == null ? "" : file.getContentType(),
            file.getSize(), file.getBytes())));
}

@DeleteMapping("/{agentId}/media")
public WebResult<AgentProfileViewDTO> removeMedia(@PathVariable("agentId") UUID agentId,
                                                  @RequestParam("kind") String kind,
                                                  @RequestParam("url") String url) {
    return ok(AgentProfileViewDTO.from(storefrontAppService.removeMedia(
            agentId, currentUser.currentUserId(), kind, url)));
}

@GetMapping("/{agentId}/reviews")
public WebResult<List<ReviewDTO>> reviews(@PathVariable("agentId") UUID agentId) {
    return ok(storefrontAppService.reviews(agentId, currentUser.currentUserId())
            .stream().map(ReviewDTO::from).toList());
}

@PutMapping("/{agentId}/reviews/{reviewId}/response")
public WebResult<ReviewDTO> respond(@PathVariable("agentId") UUID agentId,
                                    @PathVariable("reviewId") UUID reviewId,
                                    @Valid @RequestBody RespondReviewRequest request) {
    return ok(ReviewDTO.from(storefrontAppService.respondToReview(
            agentId, currentUser.currentUserId(), reviewId, request.response())));
}
```

Slice test `AgentControllerStorefrontTest` (`@WebMvcTest(AgentController.class)` + the usual imports; `@MockBean` ALL of the controller's services — `AgentWriteAppService`, `AgentReadAppService`, `AgentStorefrontAppService`, `CurrentUserProvider`): PUT profile happy (asserts JSON round-trip); GET profile of foreign agent → service throws NOT_FOUND → 404; multipart upload via `MockMvcRequestBuilders.multipart("/api/agents/{id}/media", id).file(new MockMultipartFile("file", "x.png", "image/png", bytes)).param("kind", "logo")` → 200; PUT review response happy → 200 with `builderResponse` set.

- [ ] **Step 6: Run slice + full suite, commit**

```bash
mvn -f backend/pom.xml -B test
git add backend/src/main backend/src/test
git commit -m "feat: builder storefront management - profile, media upload, review responses"
```

### Task 14: Backend — pricing update (TDD)

**Files:**
- Modify: `AgentVersionModel.java` (add `updateCommercials`), `AgentRepository.java` + `AgentRepositoryImpl.java` (add `updateCurrentVersion`), `AgentWriteAppService.java` + impl (add `updatePricing`), `AgentController.java` (PUT `/{agentId}/pricing`)
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/dto/UpdatePricingRequest.java`, `backend/src/main/java/com/hireai/domain/biz/agent/info/PricingUpdateInfo.java`
- Tests: `backend/src/test/java/com/hireai/agent/AgentVersionModelTest.java` (append or create), slice additions

- [ ] **Step 1: Failing domain test** — `updateCommercials` returns a copy with same `id`/`versionNumber`/`webhookUrl`/`outputSpec` but new price/maxExec/normalised-lowercase categories; rejects `maxExecutionSeconds <= 0`, empty categories (reuses the factory's normalise/validate helpers).
- [ ] **Step 2: Implement** on `AgentVersionModel`:

```java
/** In-place commercial update for this slice (spec §9: no version history yet). */
public AgentVersionModel updateCommercials(Pricing pricing, int maxExecutionSeconds,
                                           List<String> capabilityCategories) {
    requirePresent(pricing, "pricing");
    List<String> normalised = normaliseCategories(capabilityCategories);
    if (maxExecutionSeconds <= 0) {
        throw new DomainException(ResultCode.VALIDATION_ERROR, "maxExecutionSeconds must be positive");
    }
    return new AgentVersionModel(id, agentId, versionNumber, outputSpec, normalised,
            webhookUrl, maxExecutionSeconds, pricing, createdAt);
}
```

`AgentRepository.updateCurrentVersion(AgentVersionModel version)` → impl saves the version entity unconditionally (overwrite by `@Id`), preserving `gmt_create` (read existing first, like `AgentProfileRepositoryImpl.save`). `PricingUpdateInfo(BigDecimal price, int maxExecutionSeconds, List<String> capabilityCategories)`. `AgentWriteAppService.updatePricing(agentId, ownerId, info)` impl: `AgentModel agent = readAppService.getForOwner(...)` (or repo+owner check matching the class's existing style — mirror `activate`), then `agentRepository.updateCurrentVersion(agent.currentVersion().updateCommercials(Pricing.of(info.price()), info.maxExecutionSeconds(), info.capabilityCategories()))`.

`UpdatePricingRequest`:

```java
public record UpdatePricingRequest(
        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.DecimalMin("0.00") java.math.BigDecimal price,
        @jakarta.validation.constraints.Min(1) int maxExecutionSeconds,
        @jakarta.validation.constraints.NotEmpty java.util.List<String> capabilityCategories) {
}
```

Controller: `PUT /{agentId}/pricing` → `writeAppService.updatePricing(...)` then return refreshed `AgentDTO` via `readAppService.getForOwner` (mirrors `activate`). Slice test: happy 200 with updated price; foreign agent → 404.

- [ ] **Step 3: Run, full suite, commit**

```bash
mvn -f backend/pom.xml -B test
git add backend/src/main backend/src/test
git commit -m "feat: in-place pricing/turnaround/categories update for the current agent version"
```

### Task 15: Backend — builder stats endpoint (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/port/query/BuilderStatsQueryPort.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/catalogue/JdbcBuilderStatsQueryDao.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/agent/dto/AgentStatsDTO.java`
- Modify: `AgentStorefrontAppService` + impl (add `getStats`), `AgentController` (GET `/{agentId}/stats`)
- Tests: `backend/src/test/java/com/hireai/agent/BuilderStatsQueryDaoIntegrationTest.java`, slice addition

- [ ] **Step 1: Port + DTO contracts**

```java
package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Builder-private statistics over real tasks routed to any version of the agent. */
public interface BuilderStatsQueryPort {

    StatsRow stats(UUID agentId);

    List<TrendPointRow> trend(UUID agentId, int days);

    List<RecentTaskRow> recentTasks(UUID agentId, int limit);

    record StatsRow(int total, int completed, int failed, int open,
                    BigDecimal creditsInEscrow, BigDecimal potentialEarnings,
                    Double avgTurnaroundSeconds, int onTimeCount, int withResultCount) {
    }

    record TrendPointRow(LocalDate day, int count) {
    }

    record RecentTaskRow(UUID id, String title, String status, Instant createdAt) {
    }
}
```

```java
// controller/biz/agent/dto/AgentStatsDTO.java
package com.hireai.controller.biz.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AgentStatsDTO(Volume volume, Performance performance, Earnings earnings,
                            List<TrendPoint> trend, List<RecentTask> recentTasks) {
    public record Volume(int total, int completed, int failed, int open, Double successRate) {}
    public record Performance(Double avgTurnaroundSeconds, Double onTimeRate) {}
    public record Earnings(BigDecimal creditsInEscrow, BigDecimal potentialEarnings) {}
    public record TrendPoint(LocalDate day, int count) {}
    public record RecentTask(UUID id, String title, String status, Instant createdAt) {}
}
```

- [ ] **Step 2: Failing DAO integration test** — seed (via `JdbcTemplate`) one agent + version, then tasks with `agent_version_id` set: 2 × `RESULT_RECEIVED` (one with a `task_results` row 30s after `gmt_create`), 1 × `FAILED`, 1 × `EXECUTING` (budgets 10/20/30/40). Assert: `total=4, completed=2, failed=1, open=1`; `creditsInEscrow` = 10+20+40 = 70 (everything not failed/terminal — see SQL comment); `potentialEarnings` = 30 (completed budgets); `avgTurnaroundSeconds ≈ 30`; trend has a point for today with count 4; `recentTasks` newest-first.

- [ ] **Step 3: Implement the DAO**

```java
package com.hireai.infrastructure.repository.catalogue;

import com.hireai.application.port.query.BuilderStatsQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Builder stats over tasks joined through agent_versions. "creditsInEscrow" = budgets of
 * tasks still holding escrow (no settlement exists yet, so RESULT_RECEIVED still holds);
 * failed/timed-out/spec-violation are excluded as future-refund, RESOLVED/CANCELLED as exits.
 * Labelled in the UI as pending Module 5. Turnaround = received_at - task creation.
 */
@Repository
public class JdbcBuilderStatsQueryDao implements BuilderStatsQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBuilderStatsQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StatsRow stats(UUID agentId) {
        String sql = """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE tk.status IN ('RESULT_RECEIVED','RESOLVED')) AS completed,
                       COUNT(*) FILTER (WHERE tk.status IN ('FAILED','TIMED_OUT','SPEC_VIOLATION')) AS failed,
                       COUNT(*) FILTER (WHERE tk.status IN
                           ('SUBMITTED','QUEUED','EXECUTING','AWAITING_CAPACITY','PENDING_REVIEW')) AS open_count,
                       COALESCE(SUM(tk.budget) FILTER (WHERE tk.status NOT IN
                           ('RESOLVED','CANCELLED','FAILED','TIMED_OUT','SPEC_VIOLATION')), 0) AS credits_in_escrow,
                       COALESCE(SUM(tk.budget) FILTER (WHERE tk.status IN
                           ('RESULT_RECEIVED','RESOLVED')), 0) AS potential_earnings,
                       AVG(EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create))) AS avg_turnaround_seconds,
                       COUNT(*) FILTER (WHERE tr.received_at IS NOT NULL
                           AND EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create)) <= v.max_execution_seconds) AS on_time,
                       COUNT(*) FILTER (WHERE tr.received_at IS NOT NULL) AS with_result
                FROM tasks tk
                JOIN agent_versions v ON v.id = tk.agent_version_id
                LEFT JOIN task_results tr ON tr.task_id = tk.id
                WHERE v.agent_id = :agentId
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("agentId", agentId),
                (rs, i) -> new StatsRow(rs.getInt("total"), rs.getInt("completed"),
                        rs.getInt("failed"), rs.getInt("open_count"),
                        rs.getBigDecimal("credits_in_escrow"), rs.getBigDecimal("potential_earnings"),
                        (Double) rs.getObject("avg_turnaround_seconds"),
                        rs.getInt("on_time"), rs.getInt("with_result")));
    }

    @Override
    public List<TrendPointRow> trend(UUID agentId, int days) {
        String sql = """
                SELECT date_trunc('day', tk.gmt_create)::date AS day, COUNT(*) AS cnt
                FROM tasks tk JOIN agent_versions v ON v.id = tk.agent_version_id
                WHERE v.agent_id = :agentId AND tk.gmt_create > now() - make_interval(days => :days)
                GROUP BY day ORDER BY day
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId).addValue("days", days);
        return jdbc.query(sql, params, (rs, i) ->
                new TrendPointRow(rs.getDate("day").toLocalDate(), rs.getInt("cnt")));
    }

    @Override
    public List<RecentTaskRow> recentTasks(UUID agentId, int limit) {
        String sql = """
                SELECT tk.id, tk.title, tk.status, tk.gmt_create
                FROM tasks tk JOIN agent_versions v ON v.id = tk.agent_version_id
                WHERE v.agent_id = :agentId
                ORDER BY tk.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId).addValue("limit", limit);
        return jdbc.query(sql, params, (rs, i) -> new RecentTaskRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"),
                rs.getTimestamp("gmt_create").toInstant()));
    }
}
```

- [ ] **Step 4: App service + controller** — `AgentStorefrontAppService.getStats(agentId, ownerId)` (owner gate, then port; compose nothing — return a small record bundling the three port results, defined on the port: `record StatsBundle(StatsRow stats, List<TrendPointRow> trend, List<RecentTaskRow> recent) {}`). Controller `GET /{agentId}/stats` maps bundle → `AgentStatsDTO` (successRate = completed/total when total>0 else null; onTimeRate = onTime/withResult when withResult>0 else null; trend 14 days; recent 10). Slice test: happy 200 with computed rates; foreign → 404.

- [ ] **Step 5: Run, full suite, commit**

```bash
mvn -f backend/pom.xml -B test
git add backend/src/main backend/src/test
git commit -m "feat: builder agent stats - volume, performance, escrow earnings, trend, activity"
```

### Task 16: Frontend — builder manage page (tabs) + dashboard links (TDD)

**Files:**
- Create: `frontend/components/StatTile.tsx`, `frontend/components/Sparkline.tsx`, `frontend/components/MediaUploader.tsx`
- Create: `frontend/app/builder/agents/[id]/page.tsx`
- Modify: `frontend/app/builder/page.tsx` (add a "Manage ▸" link per agent card → `/builder/agents/${a.id}`)
- Modify: `frontend/lib/types.ts` (builder DTOs), `frontend/test/msw/handlers.ts` (profile/stats/reviews/media/pricing handlers)
- Test: `frontend/test/manage.test.tsx`

- [ ] **Step 1: Types** (append to `lib/types.ts`)

```typescript
// ── Builder storefront management ──

export interface AgentProfileViewDTO {
  tagline: string | null;
  description: string | null;
  sampleOutput: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  galleryUrls: string[];
  listed: boolean;
  featured: boolean;
}

export interface UpdateProfileRequest {
  tagline: string | null;
  description: string | null;
  sampleOutput: string | null;
  isListed: boolean;
}

export interface UpdatePricingRequest {
  price: number;
  maxExecutionSeconds: number;
  capabilityCategories: string[];
}

export interface BuilderReviewDTO {
  id: string;
  rating: number;
  reviewText: string | null;
  builderResponse: string | null;
  createdAt: string;
}

export interface AgentStatsDTO {
  volume: { total: number; completed: number; failed: number; open: number; successRate: number | null };
  performance: { avgTurnaroundSeconds: number | null; onTimeRate: number | null };
  earnings: { creditsInEscrow: number; potentialEarnings: number };
  trend: { day: string; count: number }[];
  recentTasks: { id: string; title: string; status: TaskStatus; createdAt: string }[];
}

export type MediaKind = "logo" | "cover" | "gallery";
```

- [ ] **Step 2: MSW handlers** — add to `handlers.ts`: `GET */api/agents/:id/profile` (returns a mutable in-module `profileState`), `PUT */api/agents/:id/profile` (merges body into `profileState`, returns it), `POST */api/agents/:id/media` (returns `profileState` with `logoUrl: "https://cdn.test/logo.png"`), `PUT */api/agents/:id/pricing` (returns the Task-9 agent DTO with the new price), `GET */api/agents/:id/stats` (fixed stats fixture: total 7, completed 6, failed 1, open 0, successRate 0.857, escrow 40, potential 60, 3 trend points, 2 recent tasks), `GET */api/agents/:id/reviews` (one review, no response), `PUT */api/agents/:id/reviews/:rid/response` (returns the review with `builderResponse` = body.response). Export a `resetProfileState()` and call it in tests' `afterEach`.

- [ ] **Step 3: Failing test `manage.test.tsx`** (harness like builder.test.tsx with BUILDER auth; `useParams: () => ({ id: "a-1" })`):
  - Storefront tab (default): shows tagline input prefilled; toggling "Listed on marketplace" + Save calls PUT (assert UI reflects response, e.g. "saved" flash or updated value).
  - Stats tab: click tab → total/success/escrow tiles render (`7`, `86%`, `40`), recent task title visible.
  - Reviews tab: click tab → review text renders; type a response, submit → `findByText(/thanks/i)` after MSW echoes it.
  - Pricing tab: price input prefilled `10`; change to `12` + save → PUT called (assert success state).

- [ ] **Step 4: Implement components + page**

`StatTile.tsx`:

```tsx
export function StatTile({ value, label, tone = "fg" }: { value: string | number; label: string; tone?: "fg" | "accent" | "amber" | "red" }) {
  const toneCls = { fg: "text-fg", accent: "text-accent", amber: "text-amber", red: "text-red" }[tone];
  return (
    <div className="bg-surface px-5 py-5">
      <p className={`tabular text-3xl font-extrabold ${toneCls}`}>{value}</p>
      <p className="mt-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">{label}</p>
    </div>
  );
}
```

`Sparkline.tsx` (no chart lib — spec scope cut):

```tsx
/** Minimal SVG sparkline for request-trend points. */
export function Sparkline({ points, width = 220, height = 48 }: { points: { day: string; count: number }[]; width?: number; height?: number }) {
  if (points.length === 0) {
    return <p className="font-mono text-xs text-dim">No requests in the window.</p>;
  }
  const max = Math.max(...points.map((p) => p.count), 1);
  const step = points.length > 1 ? width / (points.length - 1) : 0;
  const coords = points.map((p, i) => `${i * step},${height - (p.count / max) * (height - 4) - 2}`);
  return (
    <svg width={width} height={height} role="img" aria-label="Requests over time" className="overflow-visible">
      <polyline points={coords.join(" ")} fill="none" stroke="var(--color-accent)" strokeWidth="2" />
      {coords.map((c) => {
        const [x, y] = c.split(",").map(Number);
        return <circle key={c} cx={x} cy={y} r="2.5" fill="var(--color-accent)" />;
      })}
    </svg>
  );
}
```

`MediaUploader.tsx` — props `{ agentId, kind, label, currentUrl?, onUploaded(profile) }`; hidden `<input type="file" accept="image/png,image/jpeg,image/webp">`; on change: client-side guard `file.size <= 2*1024*1024` (else inline error), build `FormData` (`kind`, `file`), `apiUpload<AgentProfileViewDTO>(`/agents/${agentId}/media`, form)`, call `onUploaded`, render current image thumbnail + "Replace"/"Upload" button + uploading state + `role="alert"` error.

`app/builder/agents/[id]/page.tsx` — `AppShell` + `RoleGuard role="BUILDER"`; loads in parallel `api(/agents/${id})` (AgentDTO for name/status/pricing), `api(/agents/${id}/profile)`, lazily per-tab `api(/agents/${id}/stats)` and `api(/agents/${id}/reviews)`. Local `tab` state (`storefront | pricing | stats | reviews`) rendered as mono-caps tab buttons (`role="tab"`, accent underline on active). Tab content:
  - **Storefront:** `Field`-wrapped inputs for tagline / description (textarea) / sampleOutput (textarea, mono); `MediaUploader` ×3 (logo, cover, gallery-append + thumbnails with per-image Remove calling `DELETE` via `api(..., { method: "DELETE" })` querystring); a listed toggle (`<input type="checkbox">` styled mono: "Listed on marketplace"); Save ▸ → PUT profile; show the storefront's public URL hint (`/client/agents/${id}`).
  - **Pricing:** price (number), maxExecutionSeconds (number), categories (comma-separated input → split/trim/lowercase); Save ▸ → PUT pricing; warning copy: "Edits apply to the live version immediately (no version history in this slice)."
  - **Stats:** tile grid (`grid grid-cols-2 sm:grid-cols-4 gap-px` panel pattern from `/builder`): total / completed / failed / open / success % / avg turnaround s / on-time % / `escrow {creditsInEscrow} cr` (+ caption "in escrow — settlement lands with Module 5"); `Sparkline points={stats.trend}`; recent-task list with `Badge status`.
  - **Reviews:** list (rating stars + text + date); per-review response textarea + "Respond ▸" → PUT response; existing responses shown like the public `ReviewList`.

`app/builder/page.tsx` — inside each agent card add next to Activate:

```tsx
<Link href={`/builder/agents/${a.id}`} className="mt-5 w-fit">
  <Button variant="secondary">Manage ▸</Button>
</Link>
```

(Keep the Activate button logic untouched — `builder.test.tsx` must stay green.)

- [ ] **Step 5: Run — must pass.** `npx vitest run test/manage.test.tsx`, then full `npx vitest run` + `npx tsc --noEmit`.

- [ ] **Step 6: Commit**

```bash
git add frontend/components frontend/app/builder frontend/lib/types.ts frontend/test
git commit -m "feat: builder manage console - storefront editor, media, pricing, stats, reviews"
```

# Phase 6 — Verification, docs, live E2E

### Task 17: Full-suite verification (no code changes expected)

- [ ] **Step 1: Backend** — `mvn -f backend/pom.xml -B test` → ALL green (existing ~158 + new). Integration tests need Docker running.
- [ ] **Step 2: Frontend** — in `frontend/`: `npx vitest run` → ALL green (existing 22 + new); `npx tsc --noEmit` → clean; `npm run build` → clean production build.
- [ ] **Step 3:** If anything fails: fix the IMPLEMENTATION (per testing rules, never bend a test unless the test itself is provably wrong), re-run, and only then proceed.
- [ ] **Step 4: Commit** any fixes as `fix: <what>`.

### Task 18: Documentation + runbook

**Files:** Modify `docs/details/data-model.md`, `docs/details/frontend.md`, `docs/details/demo-runbook.md`, `CLAUDE.md`

- [ ] **Step 1: `data-model.md`** — append to "Implemented so far": V6 (`agent_profiles` — columns + the ACTIVE+listed visibility rule + backfill note) and V7 (`reviews` — nullable `task_id` w/ rationale, seed). 
- [ ] **Step 2: `frontend.md`** — update the route map: `/client` = Marketplace, `/client/tasks` = console (moved), `/client/agents/[id]` + `/book`, `/builder/agents/[id]` manage tabs; document `apiUpload` beside `api()`.
- [ ] **Step 3: `demo-runbook.md`** — add the one-time Supabase Storage setup (bucket curl from Task 4 Step 5 + the two `backend/.env` vars).
- [ ] **Step 4: `CLAUDE.md`** — single-line updates only (it's an index): mark Module 6 as built on this branch (catalogue + storefront + direct booking + builder stats; reviews seeded), and the frontend "Pending" line (public catalogue done; Admin remains).
- [ ] **Step 5: Commit** — `git add docs CLAUDE.md && git commit -m "docs: record storefront/discovery slice (V6-V7, catalogue API, direct booking)"`

### Task 19: Live E2E (manual, full stack)

Stack per `docs/details/demo-runbook.md` (Supabase + RabbitMQ + backend + frontend + stub agent + cloudflared). Then verify in the browser:

- [ ] 1. Builder logs in → `/builder` → Manage on the demo agent → Storefront tab: set tagline + description + sample output, upload a logo image (lands in Supabase Storage, URL renders), toggle **Listed**, Save.
- [ ] 2. Client logs in → `/client` marketplace shows the agent (hot strip if featured, card shows rating from V7 seed reviews); search by name and by builder name; category chip filters.
- [ ] 3. Open the storefront → contract/stats/reviews/gallery render; **Book this agent** → budget prefilled; submit → redirected to task detail; pipeline runs SUBMIT→…→RESULT_RECEIVED via the stub agent; wallet shows the escrow freeze.
- [ ] 4. Builder → Stats tab shows the new request (total+1, escrow + budget); Reviews tab → respond to a seeded review → response appears on the public storefront.
- [ ] 5. Negative: book with budget below price → clear 400 error; open an unlisted agent's URL as client → "Agent not found".

---

## Self-review (run after writing, fixed inline)

1. **Spec coverage:** §1 goals→Tasks 5–12 (discover/search/category/hot, storefront, two submit paths), §4.2→Tasks 13–15, §4.4 stats→15–16, §4.5 ranking→Task 5, §5 frontend→7–12, 16, §6 security→owner gates in 13–15 + catalogue DTO exclusions in 6 + server-only storage key in 4, §7 testing→every task + 17, §8 phases→mapped 1:1. Gaps: none found.
2. **Deliberate deviations from spec (all safer/narrower):** direct booking = `POST /api/tasks/direct` (own validated request) instead of optional `agentId`; pricing = `PUT /{id}/pricing` instead of `PUT /{id}`; wallet stays inside `/client/tasks` (no separate `/client/wallet` page); `AWAITING_CAPACITY` note — current model has no capacity concept, so direct dispatch to an ACTIVE agent always dispatches (spec §2's mention is moot until capacity exists).
3. **Type consistency check:** `AgentProfileModel.listed()/featured()` naming used consistently (model) vs `isListed` (JSON/request fields); `CatalogueQueryPort` row records consumed by Task 6 controller match Task 5 definitions; `StatsBundle` defined on `BuilderStatsQueryPort` (Task 15 Step 4) before use; frontend `AgentStatsDTO`/`AgentProfileViewDTO` mirror backend DTO JSON exactly (note: Jackson serialises the record accessor `isListed()`→ field name from the record component `listed`? — records serialise by COMPONENT name, so `AgentProfileViewDTO(... boolean listed, boolean featured)` emits `listed`/`featured`; the frontend types above already use `listed`/`featured`. The REQUEST record uses `isListed` as its component name, matching the frontend request type. Verified consistent.)
4. **Known verify-at-implementation points (flagged in tasks):** `OutputFormat` package, `Money` accessor, `OutputSpecJsonMapper` package/visibility, the exact native query text in `AgentVersionJpaRepository`, existing MSW `*/api/wallet`+`*/api/tasks` handlers, and `AgentWriteAppServiceImpl.register`'s local variable names. Each task says "read the file first" where this applies.

## Execution order & dependencies

Strictly sequential by task number, except: Task 12 (booking page) only needs Task 7's MSW handlers — it can run before Task 11 if backend and frontend are parallelised; Tasks 13/14/15 are independent of each other after Task 4.

