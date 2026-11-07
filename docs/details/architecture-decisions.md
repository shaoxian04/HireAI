# Architecture decisions (backend)

A lightweight ADR (architecture decision record) log for the structural and convention
choices behind `backend/` — especially the ones where HireAI **deliberately diverges** from
the eyebluecn `smart-classroom` COLA reference it was modeled on. Each entry is
**Context → Decision → Consequence**.

> These are the *why*. The *what* lives in [`ddd-conventions.md`](ddd-conventions.md) (layering,
> naming) and [`identity-and-authz.md`](identity-and-authz.md) (auth). Record new decisions in
> [`docs/adr/`](../adr/).

---

## ADR-001 — COLA multi-module Maven reactor (not a single module)

**Context.** The backend began as one Maven module with COLA-style packages. Layer boundaries
(e.g. "domain must not import infrastructure") were convention-only, enforced by review.

**Decision.** Split into a 7-module reactor: `hireai-utility → hireai-domain → hireai-application
→ {hireai-repository, hireai-infrastructure, hireai-controller} → hireai-main`.

**Consequence.** Layer dependencies are now **compiler-enforced** — `hireai-domain` and
`hireai-utility` carry no Spring on their classpath, so the domain is framework-free *by
construction*, not by discipline. Cost: 7 `pom.xml`s; only `hireai-main` is bootable, so
integration tests live there. Package names were unchanged in the split.

## ADR-002 — Aggregate-centric DDD (rich aggregates), not the reference's anemic model

**Context.** The eyebluecn reference is **service-centric**: anemic Lombok `@Data` models + fat
`@Service` domain services that hold the logic and own persistence.

**Decision.** Keep behavior and invariants in **rich aggregates** (`WalletModel` owns the ledger
+ all money invariants; `TaskModel`, `AgentModel` own their transitions). Domain services are
thin, one-per-transition coordinators that delegate to the aggregates.

**Consequence.** Business logic lives in the domain, is unit-testable without Spring, and avoids
the anemic-domain anti-pattern (Fowler, 2003). HireAI's domain services *look* thinner than the
reference's — that's correct given the rich aggregates, not a sign of a thin domain.

## ADR-003 — Application layer = thin orchestration; writes go through the domain repository interface

**Context.** Where do business rules and persistence belong?

**Decision.** Application services **only orchestrate**: open the transaction (`@Transactional`),
read via repositories, invoke the domain method, publish/handle domain events, log, and gate auth.
Business rules live in the domain (aggregate or domain service). The app service calls the
**domain-owned repository interface** to persist (the impl is in `hireai-repository`).

**Consequence.** The app layer never becomes a second home for logic. This diverges from the
reference, where the *domain service* owns `repository.insert()/update()`; HireAI's split is
cleaner and matches the project's stated principle. (Three rules that had leaked into app services
— gallery capacity, direct-booking eligibility/pricing, earnings classification — were pushed back
into the domain.)

## ADR-004 — REST controller, not an RPC facade

**Context.** The reference exposes a `facade` / `facade-impl` layer — a Dubbo-style RPC contract
for *other services* to call.

**Decision.** HireAI is a single REST backend behind a Next.js frontend, so it uses a
**`controller`** layer (`controller/biz/<route-group>`, `WebResult` envelope). No `facade`, no
`VO`, no `FacadeClient`.

**Consequence.** No inter-service RPC indirection to maintain. `DTO`/`Request`/`Converter` live in
`controller`, not a facade module.

## ADR-005 — Shared exceptions in `hireai-utility`

**Context.** Exceptions were scattered: `DomainException` in `domain/shared/exception`, the
auth/security ones in `application`.

**Decision.** Consolidate shared exception types into `com.hireai.utility.exception`.

**Consequence.** Any layer can throw them dependency-free, and `GlobalExceptionConfiguration` maps
them. Three stay local to where they're used: `ArbitrationAuthException` and
`InvalidRulingCategoryException` (handled on the callback controller), and
`SubmitOrchestrationAppServiceImpl.IdempotencyRaceException` (internal control flow).
`DomainException` sits next to `ResultCode` (which it already depended on), removing a
cross-module reference. Matches the reference's `utility/exception` placement.

## ADR-006 — JPA persistence entities named `XxxDO`

**Context.** Persistence entities were `XxxJpaEntity`; the reference names the flat DB model
`XxxDO`.

**Decision.** Rename the 11 entities to `XxxDO`. Keep the Spring Data `XxxJpaRepository`
interfaces (the JPA analogue of the reference's MyBatis `Mapper`).

**Consequence.** Convention parity with the reference; the `Model` (rich domain) vs `DO`
(persistence) split is now explicit in the names.

## ADR-007 — OAuth identities never silently link to a pre-existing local account

**Context.** OAuth login linked an incoming (provider-verified) identity to *any* local account
with a matching email, then logged in as it. But local emails are **not** independently verified
(registration does not prove ownership), so an attacker could pre-register a victim's email and
capture the victim's later Google sign-in — account takeover. Flagged HIGH by automated security
review.

**Decision.** Refuse the OAuth login when a local account already exists for the email
(`OAuthAccountLinkingDomainService`); only create-a-new-account-on-first-login is allowed.

**Consequence.** Closes the takeover vector. Trade-off: a password-account holder can't yet
self-serve "Sign in with Google" into their account — an explicit, password-re-authenticated link
flow is a follow-up. Detail in [`identity-and-authz.md`](identity-and-authz.md).
