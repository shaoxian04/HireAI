# Registration Module + Google OAuth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add email/password self-registration and Google OAuth login to HireAI, with dual-capability RBAC (every user is a `CLIENT`; `BUILDER` is opt-in via a "Become a Builder" upgrade), both minting the existing HS256 JWT.

**Architecture:** Backend-driven OAuth (Spring Security OAuth2 Client) mints our own JWT so the JWT stays the single identity transport (Hard Invariant #5). Roles move from a single `users.role` column to a `user_roles` join table; the JWT carries a `roles` claim (a set). New users get a zero-balance wallet provisioned in the same transaction. OAuth tokens reach the browser via a URL fragment.

**Tech Stack:** Spring Boot 3.3.5 (Java 21), Spring Security + `spring-boot-starter-oauth2-client`, jjwt 0.12.6, Flyway, JPA/Hibernate (`ddl-auto: validate`), Postgres; Next.js 16 (App Router) + TypeScript + vitest + MSW.

---

## Design reference

Spec: `docs/superpowers/specs/2026-06-15-registration-oauth-design.md`.

## Conventions (read once)

- **DDL/entity coupling:** `ddl-auto: validate` runs at context startup and Flyway applies **all** migrations every test run. So the schema and the JPA entities must agree at every "run the suite" checkpoint. Phase 1 therefore lands the migrations **and** the role refactor together; its single green checkpoint is the phase end. Pure unit tests (no DB) inside Phase 1 are still run as you go.
- **No attribution trailer** in commits (user's global rule); conventional-commit prefixes (`feat:`/`refactor:`/`test:`/`docs:`).
- **Commands** (run from repo root unless noted):
  - One backend test class: `mvn -f backend/pom.xml -q -B test -Dtest=ClassName`
  - Full backend suite: `mvn -f backend/pom.xml -B test`
  - Backend compile: `mvn -f backend/pom.xml -q -B test-compile`
  - Frontend tests: `npx vitest run` (run **in** `frontend/`)
  - Frontend build: `npm --prefix frontend run build`
- **UserModel canonical shape (used everywhere after Phase 1):**
  `record UserModel(UUID id, String email, String passwordHash, String displayName, Set<Role> roles, boolean active)`
- **DTO/result role shape after Phase 1:** `roles` is a `List<String>` (role names) on `AuthResult` and `LoginResponse`; the JWT claim key is `"roles"`.

---

# Phase 1 — Schema migrations + role→roles refactor

Coupled (see DDL/entity note). Land all of Phase 1, then run the full backend suite once at the end (Task 1.9). Unit-test classes touched here (`JjwtServiceTest`, `AuthAppServiceImplTest`, `JwtAuthenticationFilterTest`) are pure unit tests and can be run individually as you go.

### Task 1.1: Migrations V10–V12

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__user_roles.sql`
- Create: `backend/src/main/resources/db/migration/V11__user_identities.sql`
- Create: `backend/src/main/resources/db/migration/V12__users_display_name.sql`

- [ ] **Step 1: Write V10** (`V10__user_roles.sql`)

```sql
-- V10: Move roles to a many-to-many join table (dual-capability RBAC). Backfill from the
-- legacy single-role column, then retire it. V1/V5 are not edited (Flyway checksums); they
-- still write users.role, which this migration reads before dropping.

CREATE TABLE user_roles (
    user_id    UUID NOT NULL REFERENCES users (id),
    role       TEXT NOT NULL CHECK (role IN ('CLIENT', 'BUILDER', 'ADMIN')),
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role)
);

INSERT INTO user_roles (user_id, role)
SELECT id, role FROM users;

ALTER TABLE users DROP COLUMN role;
```

- [ ] **Step 2: Write V11** (`V11__user_identities.sql`)

```sql
-- V11: External identity links (OAuth). A table (not columns on users) so a second provider
-- is additive later. provider_subject is the provider's stable 'sub'.

CREATE TABLE user_identities (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users (id),
    provider         TEXT NOT NULL,
    provider_subject TEXT NOT NULL,
    email_at_link    TEXT,
    gmt_create       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_subject)
);

CREATE INDEX idx_user_identities_user ON user_identities (user_id);
```

- [ ] **Step 3: Write V12** (`V12__users_display_name.sql`)

```sql
-- V12: Optional human display name (from the signup form or the OAuth profile). Nullable;
-- OAuth-only accounts keep a null password_hash. Backfill the seeded demo accounts.

ALTER TABLE users ADD COLUMN display_name TEXT;

UPDATE users SET display_name = 'Demo Client'  WHERE email = 'client@hireai.local';
UPDATE users SET display_name = 'Demo Builder' WHERE email = 'builder@hireai.local';
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V10__user_roles.sql \
        backend/src/main/resources/db/migration/V11__user_identities.sql \
        backend/src/main/resources/db/migration/V12__users_display_name.sql
git commit -m "feat: V10-V12 migrations - user_roles join table, user_identities, display_name"
```

### Task 1.2: JWT carries a role set

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/port/security/JwtService.java`
- Modify: `backend/src/main/java/com/hireai/application/port/security/JwtPrincipal.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/security/impl/JjwtService.java`
- Test: `backend/src/test/java/com/hireai/infrastructure/security/JjwtServiceTest.java`

- [ ] **Step 1: Update the test first** — replace the body of `JjwtServiceTest` round-trip + add a multi-role case. Replace lines 20–29 (the `issuesAndVerifiesRoundTrip` test) and the imports for `Duration`/`UUID` stay; add `java.util.List` and `java.util.Set`.

```java
    @Test
    void issuesAndVerifiesRoundTripWithRoleSet() {
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, List.of("CLIENT", "BUILDER"), Duration.ofMinutes(5));
        JwtPrincipal principal = service.verify(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.roles()).containsExactlyInAnyOrder("CLIENT", "BUILDER");
    }
```

Also update the other `issue(...)` calls in that file (lines 33, 43, 52) from `service.issue(UUID, "ROLE", ttl)` to `service.issue(UUID, List.of("ROLE"), ttl)`. Add imports at top:

```java
import java.util.List;
```

- [ ] **Step 2: Run it to verify it fails to compile/fail**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=JjwtServiceTest`
Expected: compile failure (`issue(UUID, List, Duration)` not defined) — that's the RED.

- [ ] **Step 3: Update `JwtService` port**

```java
package com.hireai.application.port.security;

import java.time.Duration;
import java.util.Collection;
import java.util.UUID;

/**
 * Application port for issuing and verifying authentication JWTs (Hard Invariant #5). The token is
 * bound to a user id + the user's role set with a bounded TTL; the {@code JwtAuthenticationFilter}
 * verifies it on every protected request. HS256-backed impl lives in {@code infrastructure/security}.
 */
public interface JwtService {

    String issue(UUID userId, Collection<String> roles, Duration ttl);

    JwtPrincipal verify(String token);
}
```

- [ ] **Step 4: Update `JwtPrincipal`**

```java
package com.hireai.application.port.security;

import java.util.Set;
import java.util.UUID;

/**
 * Verified identity carried by an authentication JWT: the user id (subject) and the role set.
 * Returned by {@link JwtService#verify(String)} once signature and expiry pass. The filter turns
 * each role into a {@code ROLE_<role>} authority (Hard Invariant #5).
 */
public record JwtPrincipal(UUID userId, Set<String> roles) {
}
```

- [ ] **Step 5: Update `JjwtService`** — change the claim constant, `issue`, and `verify`:

Replace the `ROLE_CLAIM` constant (line 31) and the `issue`/`verify` methods (lines 42–68):

```java
    private static final String ROLES_CLAIM = "roles";
```

```java
    @Override
    public String issue(UUID userId, java.util.Collection<String> roles, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLES_CLAIM, java.util.List.copyOf(roles))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    @Override
    public JwtPrincipal verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            Object raw = claims.get(ROLES_CLAIM);
            java.util.Set<String> roles = raw instanceof java.util.Collection<?> c
                    ? c.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
                    : java.util.Set.of();
            return new JwtPrincipal(userId, roles);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtInvalidException("Invalid authentication token", ex);
        }
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=JjwtServiceTest`
Expected: PASS. (No commit yet — Phase 1 commits together at Task 1.9.)

### Task 1.3: Filter sets one authority per role

**Files:**
- Modify: `backend/src/main/java/com/hireai/controller/config/JwtAuthenticationFilter.java`
- Test: `backend/src/test/java/com/hireai/controller/config/JwtAuthenticationFilterTest.java`

- [ ] **Step 1: Update the test** — line 36 and the assertion at 46:

```java
        when(jwtService.verify("good")).thenReturn(new JwtPrincipal(userId, java.util.Set.of("CLIENT")));
```
```java
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_CLIENT");
```

- [ ] **Step 2: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=JwtAuthenticationFilterTest`
Expected: compile failure (`JwtPrincipal(UUID, String)` no longer exists).

- [ ] **Step 3: Update the filter** — replace the success block (lines 46–50):

```java
                JwtPrincipal principal = jwtService.verify(token);
                var authorities = principal.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.userId(), null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
```

- [ ] **Step 4: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=JwtAuthenticationFilterTest`
Expected: PASS.

### Task 1.4: User model + roles persistence

**Files:**
- Modify: `backend/src/main/java/com/hireai/domain/biz/user/model/UserModel.java`
- Modify: `backend/src/main/java/com/hireai/domain/biz/user/repository/UserRepository.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserRoleJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserRoleJpaRepository.java`
- Modify: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserRepositoryImpl.java`
- Test: `backend/src/test/java/com/hireai/user/UserRepositoryIntegrationTest.java`

- [ ] **Step 1: Rewrite `UserModel`**

```java
package com.hireai.domain.biz.user.model;

import com.hireai.domain.biz.user.enums.Role;

import java.util.Set;
import java.util.UUID;

/**
 * User aggregate (read + create). Dual-capability: every user holds {@code CLIENT} and may add
 * {@code BUILDER}. Roles are sourced from the {@code user_roles} join table. Immutable.
 */
public record UserModel(UUID id, String email, String passwordHash, String displayName,
                        Set<Role> roles, boolean active) {

    /** A brand-new self-serve account: random id, CLIENT role, active. */
    public static UserModel newClient(String email, String passwordHash, String displayName) {
        return new UserModel(UUID.randomUUID(), email, passwordHash, displayName, Set.of(Role.CLIENT), true);
    }
}
```

- [ ] **Step 2: Expand the domain `UserRepository`**

```java
package com.hireai.domain.biz.user.repository;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the User aggregate. Roles live in the user_roles join table and are
 * read/written through this root. The interface is framework-free; the JPA impl is in infrastructure.
 */
public interface UserRepository {

    Optional<UserModel> findByEmail(String email);

    Optional<UserModel> findById(UUID id);

    /** Inserts the user row and one user_roles row per role. */
    UserModel create(UserModel user);

    /** Idempotently grants a role (used by the become-builder upgrade). */
    void addRole(UUID userId, Role role);
}
```

- [ ] **Step 3: Rewrite `UserJpaEntity`** (drop `role`, add `display_name`, add a public constructor for the create path)

```java
package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA persistence entity for a user row. Roles live in {@link UserRoleJpaEntity}. */
@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UserJpaEntity() {
    }

    public UserJpaEntity(UUID id, String email, String passwordHash, String displayName, boolean active) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.active = active;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isActive() { return active; }
}
```

- [ ] **Step 4: Create `UserRoleJpaEntity`** (composite key via `@IdClass`; `gmt_create` left to the DB default)

```java
package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** JPA entity for one (user_id, role) grant. */
@Entity
@Table(name = "user_roles")
@IdClass(UserRoleJpaEntity.Key.class)
public class UserRoleJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role")
    private String role;

    protected UserRoleJpaEntity() {
    }

    public UserRoleJpaEntity(UUID userId, String role) {
        this.userId = userId;
        this.role = role;
    }

    public UUID getUserId() { return userId; }
    public String getRole() { return role; }

    /** Composite primary key. Must be a public class with a no-arg ctor + equals/hashCode. */
    public static class Key implements Serializable {
        private UUID userId;
        private String role;

        public Key() {
        }

        public Key(UUID userId, String role) {
            this.userId = userId;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(userId, key.userId) && Objects.equals(role, key.role);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, role);
        }
    }
}
```

- [ ] **Step 5: Create `UserRoleJpaRepository`**

```java
package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for role grants. Internal to infrastructure. */
public interface UserRoleJpaRepository extends JpaRepository<UserRoleJpaEntity, UserRoleJpaEntity.Key> {

    List<UserRoleJpaEntity> findByUserId(UUID userId);

    boolean existsByUserIdAndRole(UUID userId, String role);
}
```

- [ ] **Step 6: Rewrite `UserRepositoryImpl`**

```java
package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure impl of {@link UserRepository}. Composes the user row with its role grants. */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpa;
    private final UserRoleJpaRepository roleJpa;

    public UserRepositoryImpl(UserJpaRepository userJpa, UserRoleJpaRepository roleJpa) {
        this.userJpa = userJpa;
        this.roleJpa = roleJpa;
    }

    @Override
    public Optional<UserModel> findByEmail(String email) {
        return userJpa.findByEmail(email).map(this::toModel);
    }

    @Override
    public Optional<UserModel> findById(UUID id) {
        return userJpa.findById(id).map(this::toModel);
    }

    @Override
    public UserModel create(UserModel user) {
        userJpa.save(new UserJpaEntity(user.id(), user.email(), user.passwordHash(),
                user.displayName(), user.active()));
        for (Role role : user.roles()) {
            roleJpa.save(new UserRoleJpaEntity(user.id(), role.name()));
        }
        return user;
    }

    @Override
    public void addRole(UUID userId, Role role) {
        if (!roleJpa.existsByUserIdAndRole(userId, role.name())) {
            roleJpa.save(new UserRoleJpaEntity(userId, role.name()));
        }
    }

    private UserModel toModel(UserJpaEntity e) {
        var roles = roleJpa.findByUserId(e.getId()).stream()
                .map(r -> Role.valueOf(r.getRole()))
                .collect(Collectors.toUnmodifiableSet());
        return new UserModel(e.getId(), e.getEmail(), e.getPasswordHash(), e.getDisplayName(),
                roles, e.isActive());
    }
}
```

- [ ] **Step 7: Update `UserRepositoryIntegrationTest`** — the schema no longer has `users.role`; insert into `users` (no role) + `user_roles`, and assert `roles()`. Replace `findsUserByEmail` (lines 52–65):

```java
    @Test
    void findsUserByEmailWithRoles() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, display_name, is_active) "
                        + "VALUES (?, ?, ?, ?, true)",
                id, "repo-test@hireai.local", "$2a$10$abcdefghijklmnopqrstuv", "Repo Tester");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);

        Optional<UserModel> found = userRepository.findByEmail("repo-test@hireai.local");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().roles()).containsExactly(Role.BUILDER);
        assertThat(found.get().displayName()).isEqualTo("Repo Tester");
        assertThat(found.get().passwordHash()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(found.get().active()).isTrue();
    }
```

### Task 1.5: Login service + auth DTOs use the role set

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/AuthResult.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/auth/dto/LoginResponse.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/auth/AuthController.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/impl/AuthAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/auth/impl/AuthAppServiceImplTest.java`

- [ ] **Step 1: Update the login unit test** — replace the `UserModel` construction (lines 39, 54, 73), the `issue` stub (line 40), and the assertion (line 46):

```java
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, "A", java.util.Set.of(Role.CLIENT), true)));
        when(jwtService.issue(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("CLIENT")), any(Duration.class)))
                .thenReturn("signed.jwt.token");
```
```java
        assertThat(result.roles()).containsExactly("CLIENT");
```

For the wrong-password (line 54) and inactive (line 73) cases, change only the `UserModel` ctor to the 6-arg shape: `new UserModel(userId, "a@hireai.local", hash, "A", java.util.Set.of(Role.CLIENT), <true|false>)`.

- [ ] **Step 2: Update `AuthResult`**

```java
package com.hireai.application.biz.auth;

import java.util.List;
import java.util.UUID;

/** Result of a successful auth: the signed JWT, the user id, and the resolved role names. */
public record AuthResult(String token, UUID userId, List<String> roles) {
}
```

- [ ] **Step 3: Update `LoginResponse`**

```java
package com.hireai.controller.biz.auth.dto;

import java.util.List;
import java.util.UUID;

/** Outbound HTTP DTO for a successful login/registration: bearer token + identity + role names. */
public record LoginResponse(String token, UUID userId, List<String> roles) {
}
```

- [ ] **Step 4: Update `AuthController.login`** — line 35:

```java
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
```

- [ ] **Step 5: Update `AuthAppServiceImpl.login`** — replace lines 56–59:

```java
        java.util.List<String> roles = user.roles().stream()
                .map(com.hireai.domain.biz.user.enums.Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} logged in (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
```

- [ ] **Step 6: Run the login unit test**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthAppServiceImplTest`
Expected: PASS.

### Task 1.9: Compile + full backend suite (Phase 1 green checkpoint)

- [ ] **Step 1: Compile**

Run: `mvn -f backend/pom.xml -q -B test-compile`
Expected: BUILD SUCCESS (no stale references to `UserModel(.., Role, ..)`, `.role()`, single-arg `issue`).

- [ ] **Step 2: Full suite**

Run: `mvn -f backend/pom.xml -B test`
Expected: all green (Testcontainers classes auto-skip if Docker is absent — note in the run output which ran).

- [ ] **Step 3: Commit Phase 1**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "refactor: dual-capability RBAC - roles set in JWT, user_roles persistence, display_name"
```

---

# Phase 2 — Email/password registration

### Task 2.1: Conflict result code + exception

**Files:**
- Modify: `backend/src/main/java/com/hireai/controller/base/ResultCode.java`
- Create: `backend/src/main/java/com/hireai/application/biz/auth/EmailAlreadyRegisteredException.java`
- Modify: `backend/src/main/java/com/hireai/controller/config/GlobalExceptionConfiguration.java`

- [ ] **Step 1: Add the result code** — add to the enum (after `INSUFFICIENT_BALANCE`):

```java
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED"),
```

- [ ] **Step 2: Create the exception**

```java
package com.hireai.application.biz.auth;

/** Raised when registration is attempted with an email that already has an account. Maps to 409. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email already registered");
    }
}
```

- [ ] **Step 3: Map it in `GlobalExceptionConfiguration`** — add a handler (after `handleAuthFailure`):

```java
    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<WebResult<Void>> handleEmailTaken(EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(WebResult.error(ResultCode.EMAIL_ALREADY_REGISTERED, ex.getMessage()));
    }
```

Add the import at the top:

```java
import com.hireai.application.biz.auth.EmailAlreadyRegisteredException;
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/base/ResultCode.java \
        backend/src/main/java/com/hireai/application/biz/auth/EmailAlreadyRegisteredException.java \
        backend/src/main/java/com/hireai/controller/config/GlobalExceptionConfiguration.java
git commit -m "feat: EMAIL_ALREADY_REGISTERED result code + 409 handler"
```

### Task 2.2: Register app service (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/auth/RegisterInfo.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/AuthAppService.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/impl/AuthAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/auth/impl/AuthAppServiceRegisterTest.java`

- [ ] **Step 1: Create `RegisterInfo`**

```java
package com.hireai.application.biz.auth;

/** Inbound carrier for a registration attempt. Built by the controller from the validated DTO. */
public record RegisterInfo(String email, String password, String displayName) {
}
```

- [ ] **Step 2: Add to the `AuthAppService` interface** — add the method (keep `@Validated`/`@NonNull` style):

```java
    AuthResult register(@NonNull RegisterInfo registerInfo);
```

- [ ] **Step 3: Write the failing test** (`AuthAppServiceRegisterTest.java`)

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.EmailAlreadyRegisteredException;
import com.hireai.application.biz.auth.RegisterInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for registration: creates a CLIENT + wallet, hashes the password, rejects duplicates. */
class AuthAppServiceRegisterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthAppServiceImpl service =
            new AuthAppServiceImpl(userRepository, walletRepository, jwtService, encoder, 86400L);

    @Test
    void registersClientHashesPasswordAndProvisionsWallet() {
        when(userRepository.findByEmail("new@hireai.local")).thenReturn(Optional.empty());
        when(userRepository.create(any(UserModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issue(any(UUID.class), eq(List.of("CLIENT")), any(Duration.class)))
                .thenReturn("signed.jwt");

        AuthResult result = service.register(new RegisterInfo("new@hireai.local", "Sup3rSecret!", "Newbie"));

        assertThat(result.token()).isEqualTo("signed.jwt");
        assertThat(result.roles()).containsExactly("CLIENT");

        ArgumentCaptor<UserModel> userCaptor = ArgumentCaptor.forClass(UserModel.class);
        verify(userRepository).create(userCaptor.capture());
        UserModel created = userCaptor.getValue();
        assertThat(created.email()).isEqualTo("new@hireai.local");
        assertThat(created.displayName()).isEqualTo("Newbie");
        assertThat(encoder.matches("Sup3rSecret!", created.passwordHash())).isTrue();

        ArgumentCaptor<WalletModel> walletCaptor = ArgumentCaptor.forClass(WalletModel.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().userId()).isEqualTo(created.id());
        assertThat(walletCaptor.getValue().available().value().signum()).isZero();
    }

    @Test
    void rejectsDuplicateEmailAndProvisionsNothing() {
        when(userRepository.findByEmail("taken@hireai.local")).thenReturn(
                Optional.of(UserModel.newClient("taken@hireai.local", "h", "T")));

        assertThatThrownBy(() -> service.register(new RegisterInfo("taken@hireai.local", "whatever1!", null)))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).create(any());
        verify(walletRepository, never()).save(any());
        verify(jwtService, never()).issue(any(), anyList(), any());
    }
}
```

- [ ] **Step 4: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthAppServiceRegisterTest`
Expected: compile failure — the new constructor `(UserRepository, WalletRepository, JwtService, PasswordEncoder, long)` and `register(...)` don't exist yet.

- [ ] **Step 5: Update `AuthAppServiceImpl`** — add the `WalletRepository` dependency, widen the constructor, and add `register`. Replace the fields + constructor (lines 30–43) and add the method:

```java
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long jwtTtlSeconds;

    public AuthAppServiceImpl(UserRepository userRepository,
                              WalletRepository walletRepository,
                              JwtService jwtService,
                              PasswordEncoder passwordEncoder,
                              @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }
```

Add the method (and imports for `RegisterInfo`, `EmailAlreadyRegisteredException`, `UserModel`, `WalletModel`, `WalletRepository`, `org.springframework.transaction.annotation.Transactional`):

```java
    @Override
    @Transactional
    public AuthResult register(RegisterInfo info) {
        if (userRepository.findByEmail(info.email()).isPresent()) {
            throw new EmailAlreadyRegisteredException();
        }
        String hash = passwordEncoder.encode(info.password());
        UserModel user = userRepository.create(
                UserModel.newClient(info.email(), hash, info.displayName()));
        walletRepository.save(WalletModel.openFor(user.id()));

        java.util.List<String> roles = user.roles().stream()
                .map(com.hireai.domain.biz.user.enums.Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("Registered new user {} (roles {})", user.id(), roles);
        return new AuthResult(token, user.id(), roles);
    }
```

> The class stays `@Transactional(readOnly = true)`; `register` overrides to read-write so the user + roles + wallet commit atomically (Hard Invariant #1 — no half-provisioned account).

- [ ] **Step 6: Fix the existing login test's constructor call** — `AuthAppServiceImplTest` (line 31–32) now needs the wallet repo. Add `private final WalletRepository walletRepository = mock(WalletRepository.class);` and change the constructor to `new AuthAppServiceImpl(userRepository, walletRepository, jwtService, encoder, 86400L)`. Add the import.

- [ ] **Step 7: Run both auth service tests**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthAppServiceRegisterTest,AuthAppServiceImplTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: register app service - create CLIENT + wallet, reject duplicate email"
```

### Task 2.3: Register endpoint + DTO + security permit

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/auth/dto/RegisterRequest.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/auth/AuthController.java`
- Modify: `backend/src/main/java/com/hireai/controller/config/SecurityConfig.java`
- Test: `backend/src/test/java/com/hireai/controller/biz/auth/AuthRegisterControllerTest.java`

- [ ] **Step 1: Create `RegisterRequest`**

```java
package com.hireai.controller.biz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Inbound HTTP DTO for registration. Bean Validation at the boundary. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        @Size(max = 120) String displayName
) {
}
```

- [ ] **Step 2: Write the web-layer test** (`AuthRegisterControllerTest.java`) — `@WebMvcTest` with the test profile (permissive chain) and a mocked `AuthAppService`.

```java
package com.hireai.controller.biz.auth;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.EmailAlreadyRegisteredException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
class AuthRegisterControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAppService authAppService;

    @Test
    void registersAndReturnsToken() throws Exception {
        when(authAppService.register(any())).thenReturn(
                new AuthResult("jwt", UUID.randomUUID(), List.of("CLIENT")));

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@hireai.local\",\"password\":\"Sup3rSecret!\",\"displayName\":\"N\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").value("jwt"))
                .andExpect(jsonPath("$.data.roles[0]").value("CLIENT"));
    }

    @Test
    void rejectsShortPasswordWith400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@hireai.local\",\"password\":\"short\",\"displayName\":\"N\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returns409OnDuplicateEmail() throws Exception {
        when(authAppService.register(any())).thenThrow(new EmailAlreadyRegisteredException());

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@hireai.local\",\"password\":\"Sup3rSecret!\",\"displayName\":\"N\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }
}
```

- [ ] **Step 3: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthRegisterControllerTest`
Expected: fail — `/api/auth/register` returns 404 (endpoint missing).

- [ ] **Step 4: Add the endpoint to `AuthController`** — add imports for `RegisterRequest` and the method:

```java
    @PostMapping("/register")
    public WebResult<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = authAppService.register(
                new com.hireai.application.biz.auth.RegisterInfo(
                        request.email(), request.password(), request.displayName()));
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
    }
```

- [ ] **Step 5: Permit it in the secured chain** — in `SecurityConfig.securedFilterChain`, add after the login matcher (line 37):

```java
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/register").permitAll()
```

- [ ] **Step 6: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthRegisterControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: POST /api/auth/register endpoint + permitAll"
```

---

# Phase 3 — Google OAuth (backend-driven)

OAuth is gated behind a feature flag so the default/dev/test contexts (no Google creds) start cleanly and the existing suite is unaffected. Enabling it in a deploy requires `OAUTH2_ENABLED=true` **and** `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`.

### Task 3.1: Dependency + config

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Add the OAuth2 client starter** — in `pom.xml`, after the `spring-boot-starter-security` dependency (line 40):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>
```

- [ ] **Step 2: Add OAuth config to `application.yml`** — under the existing `spring:` block, add a `security` section (sibling of `datasource`), and extend the `hireai.auth` block:

```yaml
  security:
    oauth2:
      client:
        registration:
          google:
            # Empty by default → Spring's ClientsConfiguredCondition sees no client and creates
            # no ClientRegistrationRepository, so the app starts fine without Google creds.
            client-id: ${GOOGLE_CLIENT_ID:}
            client-secret: ${GOOGLE_CLIENT_SECRET:}
            scope: openid,email,profile
```

Extend `hireai.auth` (after `jwt-ttl-seconds`):

```yaml
    # Google OAuth login. Disabled by default; set OAUTH2_ENABLED=true + GOOGLE_CLIENT_ID/SECRET to enable.
    oauth2:
      enabled: ${OAUTH2_ENABLED:false}
      success-redirect-url: ${OAUTH2_SUCCESS_REDIRECT_URL:http://localhost:3000/auth/callback}
      failure-redirect-url: ${OAUTH2_FAILURE_REDIRECT_URL:http://localhost:3000/login}
```

- [ ] **Step 3: Verify the context still starts without creds**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthRegisterControllerTest`
Expected: PASS (OAuth2 auto-config backs off; no startup error).

- [ ] **Step 4: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml
git commit -m "feat: add oauth2-client starter + Google registration config (flag-gated)"
```

### Task 3.2: User-identity persistence

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/user/repository/UserIdentityRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserIdentityJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserIdentityJpaRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/user/UserIdentityRepositoryImpl.java`

- [ ] **Step 1: Domain repository**

```java
package com.hireai.domain.biz.user.repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for external identity links (OAuth). Keeps the User aggregate focused;
 * one repository per table per the DDD conventions.
 */
public interface UserIdentityRepository {

    /** The local user id linked to a provider's stable subject, if any. */
    Optional<UUID> findUserIdByProviderSubject(String provider, String subject);

    /** Links a provider identity to an existing local user. */
    void link(UUID userId, String provider, String subject, String emailAtLink);
}
```

- [ ] **Step 2: JPA entity**

```java
package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** JPA entity for one external identity link (user_identities). */
@Entity
@Table(name = "user_identities")
public class UserIdentityJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_subject", nullable = false)
    private String providerSubject;

    @Column(name = "email_at_link")
    private String emailAtLink;

    protected UserIdentityJpaEntity() {
    }

    public UserIdentityJpaEntity(UUID id, UUID userId, String provider, String providerSubject,
                                 String emailAtLink) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.providerSubject = providerSubject;
        this.emailAtLink = emailAtLink;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getProvider() { return provider; }
    public String getProviderSubject() { return providerSubject; }
    public String getEmailAtLink() { return emailAtLink; }
}
```

- [ ] **Step 3: Spring Data repository**

```java
package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for identity links. Internal to infrastructure. */
public interface UserIdentityJpaRepository extends JpaRepository<UserIdentityJpaEntity, UUID> {

    Optional<UserIdentityJpaEntity> findByProviderAndProviderSubject(String provider, String subject);
}
```

- [ ] **Step 4: Repository impl**

```java
package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link UserIdentityRepository}. */
@Repository
public class UserIdentityRepositoryImpl implements UserIdentityRepository {

    private final UserIdentityJpaRepository jpa;

    public UserIdentityRepositoryImpl(UserIdentityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UUID> findUserIdByProviderSubject(String provider, String subject) {
        return jpa.findByProviderAndProviderSubject(provider, subject)
                .map(UserIdentityJpaEntity::getUserId);
    }

    @Override
    public void link(UUID userId, String provider, String subject, String emailAtLink) {
        jpa.save(new UserIdentityJpaEntity(UUID.randomUUID(), userId, provider, subject, emailAtLink));
    }
}
```

- [ ] **Step 5: Compile + commit**

```bash
mvn -f backend/pom.xml -q -B test-compile
git add backend/src/main/java/com/hireai/domain/biz/user/repository/UserIdentityRepository.java \
        backend/src/main/java/com/hireai/infrastructure/repository/user
git commit -m "feat: user_identities persistence (OAuth identity links)"
```

### Task 3.3: OAuth login app service (TDD — the three resolution branches)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/auth/OAuthUserInfo.java`
- Create: `backend/src/main/java/com/hireai/application/biz/auth/OAuthAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/auth/OAuthAuthenticationException.java`
- Create: `backend/src/main/java/com/hireai/application/biz/auth/impl/OAuthAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/auth/impl/OAuthAppServiceImplTest.java`

- [ ] **Step 1: Create `OAuthUserInfo`**

```java
package com.hireai.application.biz.auth;

/** Normalized identity claims extracted from the OAuth provider's userinfo. */
public record OAuthUserInfo(String provider, String subject, String email,
                            boolean emailVerified, String displayName) {
}
```

- [ ] **Step 2: Create the service interface**

```java
package com.hireai.application.biz.auth;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

/**
 * Resolves an OAuth identity to a local account and issues our JWT (Hard Invariant #5). Resolution
 * order: existing identity link → existing email (link) → new CLIENT account. Email-based linking is
 * only safe because the provider verifies the email.
 */
@Validated
public interface OAuthAppService {

    AuthResult loginWithOAuth(@NonNull OAuthUserInfo info);
}
```

- [ ] **Step 3: Create the exception**

```java
package com.hireai.application.biz.auth;

/** Raised when an OAuth login cannot proceed (e.g. unverified email). Drives the failure redirect. */
public class OAuthAuthenticationException extends RuntimeException {

    public OAuthAuthenticationException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write the failing test** (`OAuthAppServiceImplTest.java`)

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAuthenticationException;
import com.hireai.application.biz.auth.OAuthUserInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for OAuth resolution: existing link, link-by-email, new account, unverified email. */
class OAuthAppServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final OAuthAppServiceImpl service = new OAuthAppServiceImpl(
            userRepository, identityRepository, walletRepository, jwtService, 86400L);

    private OAuthUserInfo google(String email, boolean verified) {
        return new OAuthUserInfo("google", "sub-123", email, verified, "Ada");
    }

    @Test
    void existingLinkLogsInWithoutCreatingOrLinking() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123"))
                .thenReturn(Optional.of(userId));
        when(userRepository.findById(userId)).thenReturn(Optional.of(
                new UserModel(userId, "ada@hireai.local", null, "Ada", Set.of(Role.CLIENT, Role.BUILDER), true)));
        when(jwtService.issue(eq(userId), anyList(), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.token()).isEqualTo("jwt");
        assertThat(result.roles()).containsExactlyInAnyOrder("CLIENT", "BUILDER");
        verify(userRepository, never()).create(any());
        verify(identityRepository, never()).link(any(), any(), any(), any());
    }

    @Test
    void linksByEmailWhenAccountExistsButIdentityDoesNot() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@hireai.local")).thenReturn(Optional.of(
                new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT), true)));
        when(jwtService.issue(eq(userId), anyList(), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.userId()).isEqualTo(userId);
        verify(identityRepository).link(userId, "google", "sub-123", "ada@hireai.local");
        verify(userRepository, never()).create(any());
        verify(walletRepository, never()).save(any());
    }

    @Test
    void createsNewClientWithWalletAndLinkWhenUnknown() {
        when(identityRepository.findUserIdByProviderSubject("google", "sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("ada@hireai.local")).thenReturn(Optional.empty());
        when(userRepository.create(any(UserModel.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtService.issue(any(UUID.class), eq(List.of("CLIENT")), any(Duration.class))).thenReturn("jwt");

        AuthResult result = service.loginWithOAuth(google("ada@hireai.local", true));

        assertThat(result.roles()).containsExactly("CLIENT");
        ArgumentCaptor<UserModel> userCaptor = ArgumentCaptor.forClass(UserModel.class);
        verify(userRepository).create(userCaptor.capture());
        assertThat(userCaptor.getValue().passwordHash()).isNull();
        assertThat(userCaptor.getValue().displayName()).isEqualTo("Ada");

        ArgumentCaptor<WalletModel> walletCaptor = ArgumentCaptor.forClass(WalletModel.class);
        verify(walletRepository).save(walletCaptor.capture());
        assertThat(walletCaptor.getValue().userId()).isEqualTo(userCaptor.getValue().id());
        verify(identityRepository).link(eq(userCaptor.getValue().id()), eq("google"), eq("sub-123"), eq("ada@hireai.local"));
    }

    @Test
    void rejectsUnverifiedEmail() {
        assertThatThrownBy(() -> service.loginWithOAuth(google("ada@hireai.local", false)))
                .isInstanceOf(OAuthAuthenticationException.class);
        verify(userRepository, never()).create(any());
    }
}
```

- [ ] **Step 5: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=OAuthAppServiceImplTest`
Expected: compile failure (`OAuthAppServiceImpl` does not exist).

- [ ] **Step 6: Implement `OAuthAppServiceImpl`**

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAppService;
import com.hireai.application.biz.auth.OAuthAuthenticationException;
import com.hireai.application.biz.auth.OAuthUserInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserIdentityRepository;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Resolves a verified OAuth identity to a local account and issues our JWT. Branches: existing
 * identity link → log in; existing email → link then log in; otherwise create a CLIENT + wallet +
 * link. All write paths are one transaction (Hard Invariant #1).
 */
@Service
@Slf4j
public class OAuthAppServiceImpl implements OAuthAppService {

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final WalletRepository walletRepository;
    private final JwtService jwtService;
    private final long jwtTtlSeconds;

    public OAuthAppServiceImpl(UserRepository userRepository,
                               UserIdentityRepository identityRepository,
                               WalletRepository walletRepository,
                               JwtService jwtService,
                               @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.walletRepository = walletRepository;
        this.jwtService = jwtService;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @Override
    @Transactional
    public AuthResult loginWithOAuth(OAuthUserInfo info) {
        if (!info.emailVerified()) {
            throw new OAuthAuthenticationException("OAuth email is not verified");
        }

        UserModel user = identityRepository
                .findUserIdByProviderSubject(info.provider(), info.subject())
                .flatMap(userRepository::findById)
                .orElseGet(() -> resolveByEmailOrCreate(info));

        return issue(user);
    }

    private UserModel resolveByEmailOrCreate(OAuthUserInfo info) {
        return userRepository.findByEmail(info.email())
                .map(existing -> {
                    identityRepository.link(existing.id(), info.provider(), info.subject(), info.email());
                    return existing;
                })
                .orElseGet(() -> {
                    UserModel created = userRepository.create(
                            UserModel.newClient(info.email(), null, info.displayName()));
                    walletRepository.save(WalletModel.openFor(created.id()));
                    identityRepository.link(created.id(), info.provider(), info.subject(), info.email());
                    log.info("Created OAuth user {} via {}", created.id(), info.provider());
                    return created;
                });
    }

    private AuthResult issue(UserModel user) {
        List<String> roles = user.roles().stream().map(Role::name).sorted().toList();
        String token = jwtService.issue(user.id(), roles, Duration.ofSeconds(jwtTtlSeconds));
        return new AuthResult(token, user.id(), roles);
    }
}
```

- [ ] **Step 7: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=OAuthAppServiceImplTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/auth backend/src/test/java/com/hireai/application/biz/auth/impl/OAuthAppServiceImplTest.java
git commit -m "feat: OAuth login app service - resolve/link/create + mint JWT"
```

### Task 3.4: OAuth success handler (TDD)

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/config/OAuth2AuthenticationSuccessHandler.java`
- Test: `backend/src/test/java/com/hireai/controller/config/OAuth2AuthenticationSuccessHandlerTest.java`

- [ ] **Step 1: Write the failing test** (instantiate the handler directly — no Spring context, so the profile/flag don't matter)

```java
package com.hireai.controller.config;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAppService;
import com.hireai.application.biz.auth.OAuthAuthenticationException;
import com.hireai.application.biz.auth.OAuthUserInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    private final OAuthAppService oauthAppService = mock(OAuthAppService.class);
    private final OAuth2AuthenticationSuccessHandler handler = new OAuth2AuthenticationSuccessHandler(
            oauthAppService, "http://localhost:3000/auth/callback", "http://localhost:3000/login");

    private OAuth2AuthenticationToken googleToken(Map<String, Object> attrs) {
        OAuth2User user = new DefaultOAuth2User(Set.of(), attrs, "sub");
        return new OAuth2AuthenticationToken(user, List.of(), "google");
    }

    @Test
    void redirectsToCallbackWithTokenFragmentOnSuccess() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenReturn(new AuthResult("jwt.abc", UUID.randomUUID(), List.of("CLIENT")));
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res, googleToken(Map.of(
                "sub", "sub-123", "email", "ada@hireai.local", "email_verified", true, "name", "Ada")));

        assertThat(res.getRedirectedUrl()).isEqualTo("http://localhost:3000/auth/callback#token=jwt.abc");
    }

    @Test
    void passesProviderAndClaimsThrough() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenReturn(new AuthResult("jwt", UUID.randomUUID(), List.of("CLIENT")));

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), new MockHttpServletResponse(),
                googleToken(Map.of("sub", "sub-123", "email", "ada@hireai.local",
                        "email_verified", true, "name", "Ada")));

        ArgumentCaptor<OAuthUserInfo> captor = ArgumentCaptor.forClass(OAuthUserInfo.class);
        org.mockito.Mockito.verify(oauthAppService).loginWithOAuth(captor.capture());
        assertThat(captor.getValue().provider()).isEqualTo("google");
        assertThat(captor.getValue().subject()).isEqualTo("sub-123");
        assertThat(captor.getValue().emailVerified()).isTrue();
        assertThat(captor.getValue().displayName()).isEqualTo("Ada");
    }

    @Test
    void redirectsToFailureUrlOnException() throws Exception {
        when(oauthAppService.loginWithOAuth(any(OAuthUserInfo.class)))
                .thenThrow(new OAuthAuthenticationException("nope"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res, googleToken(Map.of(
                "sub", "sub-123", "email", "ada@hireai.local", "email_verified", false, "name", "Ada")));

        assertThat(res.getRedirectedUrl()).isEqualTo("http://localhost:3000/login?error=oauth");
    }
}
```

- [ ] **Step 2: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=OAuth2AuthenticationSuccessHandlerTest`
Expected: compile failure (handler missing).

- [ ] **Step 3: Implement the handler**

```java
package com.hireai.controller.config;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.OAuthAppService;
import com.hireai.application.biz.auth.OAuthUserInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * On a successful Google handshake, resolves/links/creates the local account, mints our JWT, and
 * redirects the browser to the frontend callback with the token in a URL fragment (not sent in
 * Referer or server logs). Any failure redirects to the frontend login with ?error=oauth.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "hireai.auth.oauth2.enabled", havingValue = "true")
@Slf4j
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuthAppService oauthAppService;
    private final String successRedirectUrl;
    private final String failureRedirectUrl;

    public OAuth2AuthenticationSuccessHandler(
            OAuthAppService oauthAppService,
            @Value("${hireai.auth.oauth2.success-redirect-url}") String successRedirectUrl,
            @Value("${hireai.auth.oauth2.failure-redirect-url}") String failureRedirectUrl) {
        this.oauthAppService = oauthAppService;
        this.successRedirectUrl = successRedirectUrl;
        this.failureRedirectUrl = failureRedirectUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = token.getPrincipal();
            OAuthUserInfo info = new OAuthUserInfo(
                    token.getAuthorizedClientRegistrationId(),
                    user.getAttribute("sub"),
                    user.getAttribute("email"),
                    isVerified(user.getAttribute("email_verified")),
                    user.getAttribute("name"));

            AuthResult result = oauthAppService.loginWithOAuth(info);
            response.sendRedirect(successRedirectUrl + "#token=" + result.token());
        } catch (Exception ex) {
            log.warn("OAuth login failed: {}", ex.getMessage());
            response.sendRedirect(failureRedirectUrl + "?error=oauth");
        }
    }

    /** Google returns a boolean; tolerate the string form defensively. */
    private boolean isVerified(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equals(String.valueOf(claim));
    }
}
```

- [ ] **Step 4: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=OAuth2AuthenticationSuccessHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/config/OAuth2AuthenticationSuccessHandler.java \
        backend/src/test/java/com/hireai/controller/config/OAuth2AuthenticationSuccessHandlerTest.java
git commit -m "feat: OAuth success handler - mint JWT, fragment redirect to frontend"
```

### Task 3.5: OAuth security filter chain

**Files:**
- Modify: `backend/src/main/java/com/hireai/controller/config/SecurityConfig.java`

- [ ] **Step 1: Add the dedicated OAuth chain and order both `!test` chains.** Add imports:

```java
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
```

Add `@Order(2)` to the existing `securedFilterChain` bean method (just below its `@Profile("!test")`), and add this new bean above it:

```java
    /**
     * Dedicated chain for the Google OAuth handshake. Higher precedence than the JWT chain; scoped to
     * the OAuth endpoints only. Allows the session the authorization-request repository needs. Loads
     * only when OAuth is enabled and a client is configured.
     */
    @Bean
    @Order(1)
    @Profile("!test")
    @ConditionalOnProperty(name = "hireai.auth.oauth2.enabled", havingValue = "true")
    public SecurityFilterChain oauthFilterChain(
            HttpSecurity http,
            OAuth2AuthenticationSuccessHandler successHandler,
            @org.springframework.beans.factory.annotation.Value("${hireai.auth.oauth2.failure-redirect-url}") String failureUrl)
            throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .oauth2Login(oauth -> oauth
                        .successHandler(successHandler)
                        .failureUrl(failureUrl + "?error=oauth"));
        return http.build();
    }
```

> The JWT chain (now `@Order(2)`) has no `securityMatcher`, so it matches everything the OAuth chain doesn't. When OAuth is disabled the OAuth chain backs off and behaviour is unchanged.

- [ ] **Step 2: Compile**

Run: `mvn -f backend/pom.xml -q -B test-compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Run the security/auth tests**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthRegisterControllerTest,JwtAuthenticationFilterTest`
Expected: PASS (context still starts with OAuth disabled).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/config/SecurityConfig.java
git commit -m "feat: dedicated OAuth security chain (flag-gated, ordered above JWT chain)"
```

# Phase 4 — Become-a-Builder upgrade

### Task 4.1: `becomeBuilder` app service (TDD)

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/AuthAppService.java`
- Modify: `backend/src/main/java/com/hireai/application/biz/auth/impl/AuthAppServiceImpl.java`
- Test: `backend/src/test/java/com/hireai/application/biz/auth/impl/AuthAppServiceBecomeBuilderTest.java`

- [ ] **Step 1: Add to the interface**

```java
    AuthResult becomeBuilder(@NonNull java.util.UUID userId);
```

- [ ] **Step 2: Write the failing test** (`AuthAppServiceBecomeBuilderTest.java`)

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the become-builder upgrade: adds the role, re-issues an expanded token. */
class AuthAppServiceBecomeBuilderTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthAppServiceImpl service = new AuthAppServiceImpl(
            userRepository, walletRepository, jwtService, new BCryptPasswordEncoder(), 86400L);

    @Test
    void addsBuilderRoleAndReissuesToken() {
        UUID userId = UUID.randomUUID();
        // First load: CLIENT only. After addRole, the reload returns CLIENT + BUILDER.
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT), true)))
                .thenReturn(Optional.of(new UserModel(userId, "ada@hireai.local", "h", "Ada", Set.of(Role.CLIENT, Role.BUILDER), true)));
        when(jwtService.issue(eq(userId), eq(List.of("BUILDER", "CLIENT")), any(Duration.class)))
                .thenReturn("expanded.jwt");

        AuthResult result = service.becomeBuilder(userId);

        verify(userRepository).addRole(userId, Role.BUILDER);
        assertThat(result.token()).isEqualTo("expanded.jwt");
        assertThat(result.roles()).containsExactly("BUILDER", "CLIENT");
    }
}
```

- [ ] **Step 3: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthAppServiceBecomeBuilderTest`
Expected: compile failure (`becomeBuilder` missing).

- [ ] **Step 4: Implement `becomeBuilder`** in `AuthAppServiceImpl` (add after `register`):

```java
    @Override
    @Transactional
    public AuthResult becomeBuilder(java.util.UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
        userRepository.addRole(userId, com.hireai.domain.biz.user.enums.Role.BUILDER);

        UserModel updated = userRepository.findById(userId).orElseThrow();
        java.util.List<String> roles = updated.roles().stream()
                .map(com.hireai.domain.biz.user.enums.Role::name).sorted().toList();
        String token = jwtService.issue(userId, roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} upgraded to builder (roles {})", userId, roles);
        return new AuthResult(token, userId, roles);
    }
```

- [ ] **Step 5: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=AuthAppServiceBecomeBuilderTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/auth backend/src/test/java/com/hireai/application/biz/auth/impl/AuthAppServiceBecomeBuilderTest.java
git commit -m "feat: becomeBuilder app service - idempotent role add + token re-issue"
```

### Task 4.2: Become-builder endpoint

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/auth/dto/BecomeBuilderRequest.java`
- Modify: `backend/src/main/java/com/hireai/controller/biz/auth/AuthController.java`
- Test: `backend/src/test/java/com/hireai/controller/biz/auth/BecomeBuilderControllerTest.java`

- [ ] **Step 1: Create the DTO** (accept-terms must be true)

```java
package com.hireai.controller.biz.auth.dto;

import jakarta.validation.constraints.AssertTrue;

/** Inbound DTO for the become-builder upgrade. The user must accept the builder terms. */
public record BecomeBuilderRequest(
        @AssertTrue(message = "must accept builder terms") boolean acceptTerms
) {
}
```

- [ ] **Step 2: Write the failing test** — uses the `test` profile (permissive chain → `DevCurrentUserProvider` supplies the fixed user id).

```java
package com.hireai.controller.biz.auth;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.controller.config.DevCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@ActiveProfiles("test")
@Import(DevCurrentUserProvider.class)
class BecomeBuilderControllerTest {

    @Autowired MockMvc mvc;
    @MockBean AuthAppService authAppService;

    @Test
    void upgradesCurrentUserToBuilder() throws Exception {
        when(authAppService.becomeBuilder(eq(DevCurrentUserProvider.DEV_USER_ID)))
                .thenReturn(new AuthResult("expanded.jwt", DevCurrentUserProvider.DEV_USER_ID,
                        List.of("BUILDER", "CLIENT")));

        mvc.perform(post("/api/auth/become-builder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptTerms\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("expanded.jwt"))
                .andExpect(jsonPath("$.data.roles", org.hamcrest.Matchers.containsInAnyOrder("BUILDER", "CLIENT")));
    }

    @Test
    void rejectsWhenTermsNotAccepted() throws Exception {
        mvc.perform(post("/api/auth/become-builder").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acceptTerms\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
```

- [ ] **Step 3: Run to verify RED**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=BecomeBuilderControllerTest`
Expected: fail — endpoint 404 / `CurrentUserProvider` not injected.

- [ ] **Step 4: Add the endpoint to `AuthController`** — inject `CurrentUserProvider` and add the method. Update the constructor and fields:

```java
    private final AuthAppService authAppService;
    private final com.hireai.controller.config.CurrentUserProvider currentUserProvider;

    public AuthController(AuthAppService authAppService,
                          com.hireai.controller.config.CurrentUserProvider currentUserProvider) {
        this.authAppService = authAppService;
        this.currentUserProvider = currentUserProvider;
    }
```

Add the method:

```java
    @PostMapping("/become-builder")
    public WebResult<LoginResponse> becomeBuilder(
            @Valid @RequestBody com.hireai.controller.biz.auth.dto.BecomeBuilderRequest request) {
        AuthResult result = authAppService.becomeBuilder(currentUserProvider.currentUserId());
        return ok(new LoginResponse(result.token(), result.userId(), result.roles()));
    }
```

> No SecurityConfig change: `/api/auth/become-builder` is not in the permitAll list, so the JWT chain already requires a valid token (Hard Invariant #5). The endpoint derives identity from `CurrentUserProvider`, never from the body.

- [ ] **Step 4b: Fix the sliced register test broken by the wider constructor.** `AuthController` now needs a `CurrentUserProvider` bean, which the `@WebMvcTest` slice doesn't auto-load. Add the import to `AuthRegisterControllerTest` (from Task 2.3) so it can instantiate the controller:

```java
import com.hireai.controller.config.DevCurrentUserProvider;
import org.springframework.context.annotation.Import;
```
and add `@Import(DevCurrentUserProvider.class)` to the class (alongside `@ActiveProfiles("test")`). (Full-context `@SpringBootTest` tests are unaffected — `DevCurrentUserProvider`/`JwtCurrentUserProvider` are present by profile.)

- [ ] **Step 5: Run to verify GREEN**

Run: `mvn -f backend/pom.xml -q -B test -Dtest=BecomeBuilderControllerTest,AuthRegisterControllerTest`
Expected: PASS (both).

- [ ] **Step 6: Full backend suite + commit**

```bash
mvn -f backend/pom.xml -B test
```
Expected: all green.

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: POST /api/auth/become-builder endpoint (JWT-gated, identity from provider)"
```

# Phase 5 — Frontend

The 14 page tests seed `localStorage["hireai.auth"] = {userId, role}`. Backward-compatible `readPersisted` (normalizes legacy `{role}` → `{roles:[role]}`) plus a derived `role` keep them green untouched. Only the auth/login tests and the MSW login mock change; new pages get new tests.

Run frontend commands **in** `frontend/`.

### Task 5.1: Types + JWT decode util

**Files:**
- Modify: `frontend/lib/types.ts`
- Create: `frontend/lib/jwt.ts`
- Create: `frontend/lib/jwt.test.ts`

- [ ] **Step 1: Update `types.ts`** — replace the Auth section (lines 42–53):

```ts
// ── Auth ──

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName?: string;
}

export interface LoginResponse {
  token: string;
  userId: string;
  roles: Role[];
}
```

- [ ] **Step 2: Write the failing decode test** (`jwt.test.ts`)

```ts
import { describe, it, expect } from "vitest";
import { decodeJwt } from "./jwt";

// Helper: build an unsigned JWT with the given payload (base64url, no real signature needed for decode).
function fakeJwt(payload: object): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64(payload)}.sig`;
}

describe("decodeJwt", () => {
  it("extracts userId (sub) and the roles array", () => {
    const claims = decodeJwt(fakeJwt({ sub: "u-1", roles: ["CLIENT", "BUILDER"] }));
    expect(claims).toEqual({ userId: "u-1", roles: ["CLIENT", "BUILDER"] });
  });

  it("returns null for a malformed token", () => {
    expect(decodeJwt("not-a-jwt")).toBeNull();
  });
});
```

- [ ] **Step 3: Run to verify RED**

Run: `npx vitest run lib/jwt.test.ts`
Expected: fail — `./jwt` not found.

- [ ] **Step 4: Implement `jwt.ts`**

```ts
import type { Role } from "./types";

export interface JwtClaims {
  userId: string;
  roles: Role[];
}

/**
 * Client-side decode of our HS256 JWT payload to read identity + roles for UI gating. NOT a
 * verification — the backend verifies the signature on every API call. Returns null if unparsable.
 */
export function decodeJwt(token: string): JwtClaims | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/")));
    const roles: Role[] = Array.isArray(json.roles)
      ? json.roles
      : json.role
        ? [json.role]
        : [];
    return { userId: String(json.sub), roles };
  } catch {
    return null;
  }
}
```

- [ ] **Step 5: Run to verify GREEN**

Run: `npx vitest run lib/jwt.test.ts`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/types.ts frontend/lib/jwt.ts frontend/lib/jwt.test.ts
git commit -m "feat(fe): roles[] in LoginResponse + JWT decode util"
```

### Task 5.2: Auth context rewrite (roles, switcher, register, becomeBuilder, OAuth token)

**Files:**
- Modify: `frontend/lib/auth.tsx`
- Modify: `frontend/lib/auth.test.tsx`

- [ ] **Step 1: Rewrite `auth.tsx`**

```tsx
"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, TOKEN_KEY } from "./api";
import { decodeJwt } from "./jwt";
import type { LoginResponse, Role } from "./types";

const SESSION_KEY = "hireai.auth";
const SURFACE_KEY = "hireai.surface";

interface Session {
  userId: string;
  roles: Role[];
}

interface AuthValue {
  token: string | null;
  userId: string | null;
  roles: Role[];
  /** Derived "active" role for back-compat + single-surface chrome. */
  role: Role | null;
  hasRole: (r: Role) => boolean;
  activeSurface: Role | null;
  setActiveSurface: (r: Role) => void;
  login: (email: string, password: string) => Promise<LoginResponse>;
  register: (email: string, password: string, displayName?: string) => Promise<LoginResponse>;
  becomeBuilder: () => Promise<LoginResponse>;
  loginWithToken: (token: string) => boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthValue | null>(null);

/** Reads persisted state, normalizing the legacy `{role}` session into `{roles:[role]}`. */
function readPersisted(): { token: string | null; session: Session | null; surface: Role | null } {
  if (typeof localStorage === "undefined") return { token: null, session: null, surface: null };
  const token = localStorage.getItem(TOKEN_KEY);
  const raw = localStorage.getItem(SESSION_KEY);
  let session: Session | null = null;
  if (raw) {
    try {
      const parsed = JSON.parse(raw) as { userId: string; roles?: Role[]; role?: Role };
      const roles = parsed.roles ?? (parsed.role ? [parsed.role] : []);
      session = { userId: parsed.userId, roles };
    } catch {
      session = null;
    }
  }
  const surface = (localStorage.getItem(SURFACE_KEY) as Role | null) ?? null;
  return { token, session, surface };
}

/** Picks the home route for a role set: client surface unless builder-only. */
function homeFor(roles: Role[]): "/client" | "/builder" {
  return roles.includes("CLIENT") ? "/client" : "/builder";
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [surface, setSurface] = useState<Role | null>(null);

  useEffect(() => {
    const { token: t, session: s, surface: sf } = readPersisted();
    setToken(t);
    setSession(s);
    setSurface(sf);
  }, []);

  const persist = useCallback((tkn: string, s: Session) => {
    localStorage.setItem(TOKEN_KEY, tkn);
    localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    setToken(tkn);
    setSession(s);
    const sf = s.roles.includes("CLIENT") ? "CLIENT" : (s.roles[0] ?? null);
    if (sf) {
      localStorage.setItem(SURFACE_KEY, sf);
      setSurface(sf);
    }
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await api<LoginResponse>("/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      });
      persist(res.token, { userId: res.userId, roles: res.roles });
      return res;
    },
    [persist],
  );

  const register = useCallback(
    async (email: string, password: string, displayName?: string) => {
      const res = await api<LoginResponse>("/auth/register", {
        method: "POST",
        body: JSON.stringify({ email, password, displayName }),
      });
      persist(res.token, { userId: res.userId, roles: res.roles });
      return res;
    },
    [persist],
  );

  const becomeBuilder = useCallback(async () => {
    const res = await api<LoginResponse>("/auth/become-builder", {
      method: "POST",
      body: JSON.stringify({ acceptTerms: true }),
    });
    persist(res.token, { userId: res.userId, roles: res.roles });
    localStorage.setItem(SURFACE_KEY, "BUILDER");
    setSurface("BUILDER");
    return res;
  }, [persist]);

  const loginWithToken = useCallback(
    (tkn: string) => {
      const claims = decodeJwt(tkn);
      if (!claims || claims.roles.length === 0) return false;
      persist(tkn, { userId: claims.userId, roles: claims.roles });
      return true;
    },
    [persist],
  );

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(SESSION_KEY);
    localStorage.removeItem(SURFACE_KEY);
    setToken(null);
    setSession(null);
    setSurface(null);
  }, []);

  const setActiveSurface = useCallback((r: Role) => {
    localStorage.setItem(SURFACE_KEY, r);
    setSurface(r);
  }, []);

  const value = useMemo<AuthValue>(() => {
    const roles = session?.roles ?? [];
    const activeSurface = surface && roles.includes(surface) ? surface : (roles[0] ?? null);
    return {
      token,
      userId: session?.userId ?? null,
      roles,
      role: activeSurface,
      hasRole: (r: Role) => roles.includes(r),
      activeSurface,
      setActiveSurface,
      login,
      register,
      becomeBuilder,
      loginWithToken,
      logout,
    };
  }, [token, session, surface, setActiveSurface, login, register, becomeBuilder, loginWithToken, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}

export { homeFor };
```

- [ ] **Step 2: Update `auth.test.tsx`** — the login mock now returns `roles`; add coverage for register/becomeBuilder/loginWithToken/hasRole. Replace lines 22–26 (`loginOk`) and add tests:

```tsx
const loginOk = () =>
  new Response(
    JSON.stringify({
      success: true, code: "OK", message: "",
      data: { token: "jwt-123", userId: "u1", roles: ["CLIENT"] },
    }),
    { status: 200 },
  );
```

Add these tests inside the `describe` block (the existing four still pass — the legacy `{role:"BUILDER"}` rehydrate test exercises the back-compat normalization):

```tsx
  it("loginWithToken decodes roles from a JWT", () => {
    function H() {
      const { loginWithToken, roles } = useAuth();
      return (
        <div>
          <span data-testid="roles">{roles.join(",") || "none"}</span>
          <button onClick={() => loginWithToken(makeJwt(["CLIENT", "BUILDER"]))}>oauth</button>
        </div>
      );
    }
    render(<AuthProvider><H /></AuthProvider>);
    act(() => { screen.getByText("oauth").click(); });
    expect(screen.getByTestId("roles").textContent).toBe("CLIENT,BUILDER");
  });
```

Add this helper near the top of the file (after imports):

```tsx
function makeJwt(roles: string[]): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u1", roles })}.sig`;
}
```

- [ ] **Step 3: Run the auth tests**

Run: `npx vitest run lib/auth.test.tsx`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/auth.tsx frontend/lib/auth.test.tsx
git commit -m "feat(fe): auth context - roles, surface switcher, register/becomeBuilder/loginWithToken"
```

### Task 5.3: MSW login mock + register/become-builder handlers

**Files:**
- Modify: `frontend/test/msw/handlers.ts`

- [ ] **Step 1: Update the login handler** (lines 37–44) and add two handlers right after it:

```ts
  http.post("*/api/auth/login", async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string };
    if (body.password !== "pw") return fail("BAD_CREDENTIALS", "Bad credentials", 400);
    const roles = body.email.startsWith("builder") ? ["BUILDER"] : ["CLIENT"];
    return ok({ token: "test-jwt", userId: "u-1", roles });
  }),

  http.post("*/api/auth/register", async ({ request }) => {
    const body = (await request.json()) as { email: string; password: string };
    if (body.email === "taken@test.local") return fail("EMAIL_ALREADY_REGISTERED", "Email already registered", 409);
    return ok({ token: "test-jwt", userId: "u-new", roles: ["CLIENT"] });
  }),

  http.post("*/api/auth/become-builder", () =>
    ok({ token: "expanded-jwt", userId: "u-1", roles: ["CLIENT", "BUILDER"] }),
  ),
```

- [ ] **Step 2: Run the login screen test** (verifies the new login shape still routes correctly)

Run: `npx vitest run test/login.test.tsx`
Expected: PASS (login page reads `res.roles`; updated in Task 5.4).

> If this fails before Task 5.4, that's expected — the login page still reads `res.role`. Proceed to 5.4, then re-run.

- [ ] **Step 3: Commit**

```bash
git add frontend/test/msw/handlers.ts
git commit -m "test(fe): MSW auth handlers - roles[] login, register, become-builder"
```

### Task 5.4: Login page — Google button, register link, roles routing

**Files:**
- Modify: `frontend/app/login/page.tsx`

- [ ] **Step 1: Update the redirect to use `roles`** — replace line 30:

```tsx
      router.replace(res.roles.includes("BUILDER") && !res.roles.includes("CLIENT") ? "/builder" : "/client");
```

- [ ] **Step 2: Add the Google button + register link.** After the `</form>` close (line 100) but inside the `panel` div, add the OAuth button and a divider; and below the demo block add a register link. Add the API origin constant near the top (after the imports):

```tsx
const API_ORIGIN = process.env.NEXT_PUBLIC_API_ORIGIN ?? "http://localhost:8080";
```

Insert after the `</form>` (before the closing `</div>` of `panel hud`):

```tsx
            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <a
              href={`${API_ORIGIN}/oauth2/authorization/google`}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-line bg-surface px-3 py-2.5 font-mono text-xs uppercase tracking-wider text-fg transition hover:border-accent/50"
            >
              Continue with Google
            </a>
```

Add the register link after the demo block's closing `</div>` (after line 125):

```tsx
          <p className="mt-5 text-center font-mono text-xs text-muted">
            No account?{" "}
            <Link href="/register" className="text-accent hover:underline">
              Create one
            </Link>
          </p>
```

- [ ] **Step 3: Run the login test**

Run: `npx vitest run test/login.test.tsx`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add frontend/app/login/page.tsx
git commit -m "feat(fe): login page - Google button, register link, roles routing"
```

### Task 5.5: Register page (TDD)

**Files:**
- Create: `frontend/app/register/page.tsx`
- Create: `frontend/test/register.test.tsx`

- [ ] **Step 1: Write the failing test** (`register.test.tsx`)

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import RegisterPage from "@/app/register/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useParams: () => ({}),
}));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  replace.mockClear();
});
afterAll(() => server.close());

function renderRegister() {
  return render(<AuthProvider><RegisterPage /></AuthProvider>);
}

describe("register screen", () => {
  it("registers a CLIENT and redirects to /client", async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/email/i), "new@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "Sup3rSecret!");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/client"));
    expect(localStorage.getItem("hireai.token")).toBeTruthy();
  });

  it("shows an error when the email is already registered", async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/email/i), "taken@test.local");
    await userEvent.type(screen.getByLabelText(/password/i), "Sup3rSecret!");
    await userEvent.click(screen.getByRole("button", { name: /create account/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/already registered/i);
  });
});
```

- [ ] **Step 2: Run to verify RED**

Run: `npx vitest run test/register.test.tsx`
Expected: fail — `@/app/register/page` not found.

- [ ] **Step 3: Implement `register/page.tsx`** (mirrors the login page's structure/aesthetic)

```tsx
"use client";

import { useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Field, Input } from "@/components/ui";

const API_ORIGIN = process.env.NEXT_PUBLIC_API_ORIGIN ?? "http://localhost:8080";

export default function RegisterPage() {
  const { register } = useAuth();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await register(email, password, displayName || undefined);
      router.replace("/client");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Registration failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen flex-col">
      <div className="mx-auto flex w-full max-w-6xl items-center px-5 py-6 sm:px-6">
        <Link href="/" className="flex items-center gap-2.5">
          <span className="grid size-7 place-items-center rounded-[5px] border border-accent/50 bg-accent/10 glow">
            <span className="size-2.5 rounded-[2px] bg-accent" />
          </span>
          <span className="font-mono text-sm font-bold tracking-[0.22em]">
            HIRE<span className="text-accent">AI</span>
          </span>
        </Link>
      </div>

      <div className="flex flex-1 items-center justify-center px-5 pb-20">
        <div className="w-full max-w-sm">
          <div className="mb-6">
            <p className="eyebrow flex items-center gap-2">
              <span className="inline-block h-px w-6 bg-accent" />
              Create account
            </p>
            <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Join the marketplace</h1>
            <p className="mt-2 text-sm text-muted">Start as a client — you can become a builder anytime.</p>
          </div>

          <div className="panel hud p-6">
            <form onSubmit={onSubmit} className="space-y-4">
              <Field label="Name" htmlFor="displayName">
                <Input id="displayName" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
              </Field>
              <Field label="Email" htmlFor="email">
                <Input id="email" type="email" autoComplete="username" value={email}
                  onChange={(e) => setEmail(e.target.value)} required />
              </Field>
              <Field label="Password" htmlFor="password">
                <Input id="password" type="password" autoComplete="new-password" value={password}
                  onChange={(e) => setPassword(e.target.value)} required minLength={8} />
              </Field>
              {error && (
                <p role="alert" className="rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
                  {error}
                </p>
              )}
              <Button type="submit" disabled={busy} className="w-full">
                {busy ? "Creating…" : "Create account ▸"}
              </Button>
            </form>

            <div className="my-4 flex items-center gap-3">
              <span className="h-px flex-1 bg-line" />
              <span className="font-mono text-[0.6rem] uppercase tracking-[0.18em] text-dim">or</span>
              <span className="h-px flex-1 bg-line" />
            </div>
            <a href={`${API_ORIGIN}/oauth2/authorization/google`}
              className="flex w-full items-center justify-center gap-2 rounded-md border border-line bg-surface px-3 py-2.5 font-mono text-xs uppercase tracking-wider text-fg transition hover:border-accent/50">
              Continue with Google
            </a>
          </div>

          <p className="mt-5 text-center font-mono text-xs text-muted">
            Already have an account?{" "}
            <Link href="/login" className="text-accent hover:underline">Sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify GREEN**

Run: `npx vitest run test/register.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/register/page.tsx frontend/test/register.test.tsx
git commit -m "feat(fe): register page (email/password + Google)"
```

### Task 5.6: OAuth callback page (TDD)

**Files:**
- Create: `frontend/app/auth/callback/page.tsx`
- Create: `frontend/test/callback.test.tsx`

- [ ] **Step 1: Write the failing test** (`callback.test.tsx`)

```tsx
import { describe, it, expect, afterEach, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import { AuthProvider } from "@/lib/auth";
import CallbackPage from "@/app/auth/callback/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

function makeJwt(roles: string[]): string {
  const b64 = (o: object) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${b64({ alg: "HS256" })}.${b64({ sub: "u-oauth", roles })}.sig`;
}

afterEach(() => {
  localStorage.clear();
  replace.mockClear();
  window.location.hash = "";
});

describe("OAuth callback", () => {
  it("stores the fragment token and routes to /client", async () => {
    const jwt = makeJwt(["CLIENT"]);
    window.location.hash = `#token=${jwt}`;
    render(<AuthProvider><CallbackPage /></AuthProvider>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/client"));
    expect(localStorage.getItem("hireai.token")).toBe(jwt);
  });

  it("routes to /login on error", async () => {
    window.location.hash = "";
    Object.defineProperty(window, "location", {
      value: { ...window.location, hash: "", search: "?error=oauth" },
      writable: true,
    });
    render(<AuthProvider><CallbackPage /></AuthProvider>);
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/login?error=oauth"));
  });
});
```

- [ ] **Step 2: Run to verify RED**

Run: `npx vitest run test/callback.test.tsx`
Expected: fail — page not found.

- [ ] **Step 3: Implement `auth/callback/page.tsx`**

```tsx
"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth, homeFor } from "@/lib/auth";
import { decodeJwt } from "@/lib/jwt";

/**
 * Lands here after the backend OAuth success redirect: `/auth/callback#token=<jwt>`. Reads the token
 * from the URL fragment (never sent to a server), stores it, scrubs the fragment from history, and
 * routes by role. Any error param routes back to /login.
 */
export default function CallbackPage() {
  const { loginWithToken } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (typeof window === "undefined") return;

    if (window.location.search.includes("error=")) {
      router.replace("/login?error=oauth");
      return;
    }

    const hash = window.location.hash.startsWith("#") ? window.location.hash.slice(1) : window.location.hash;
    const token = new URLSearchParams(hash).get("token");
    if (!token || !loginWithToken(token)) {
      router.replace("/login?error=oauth");
      return;
    }

    window.history.replaceState(null, "", window.location.pathname);
    const claims = decodeJwt(token);
    router.replace(claims ? homeFor(claims.roles) : "/client");
  }, [loginWithToken, router]);

  return (
    <div className="flex min-h-screen items-center justify-center">
      <p className="font-mono text-sm text-muted">Signing you in…</p>
    </div>
  );
}
```

- [ ] **Step 4: Run to verify GREEN**

Run: `npx vitest run test/callback.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/auth/callback/page.tsx frontend/test/callback.test.tsx
git commit -m "feat(fe): OAuth callback page (fragment token → session → route by role)"
```

### Task 5.7: Become-a-Builder page (TDD)

**Files:**
- Create: `frontend/app/client/become-builder/page.tsx`
- Create: `frontend/test/becomeBuilder.test.tsx`

- [ ] **Step 1: Write the failing test** (`becomeBuilder.test.tsx`)

```tsx
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { server } from "./msw/handlers";
import { AuthProvider } from "@/lib/auth";
import BecomeBuilderPage from "@/app/client/become-builder/page";

const replace = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace, push: vi.fn() }) }));

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  replace.mockClear();
});
afterAll(() => server.close());

function renderPage() {
  localStorage.setItem("hireai.token", "t");
  localStorage.setItem("hireai.auth", JSON.stringify({ userId: "u-1", roles: ["CLIENT"] }));
  return render(<AuthProvider><BecomeBuilderPage /></AuthProvider>);
}

describe("become-builder", () => {
  it("upgrades to builder after accepting terms and routes to /builder", async () => {
    renderPage();
    await userEvent.click(screen.getByLabelText(/accept/i));
    await userEvent.click(screen.getByRole("button", { name: /become a builder/i }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/builder"));
    expect(localStorage.getItem("hireai.token")).toBe("expanded-jwt");
  });

  it("keeps the button disabled until terms are accepted", () => {
    renderPage();
    expect(screen.getByRole("button", { name: /become a builder/i })).toBeDisabled();
  });
});
```

- [ ] **Step 2: Run to verify RED**

Run: `npx vitest run test/becomeBuilder.test.tsx`
Expected: fail — page not found.

- [ ] **Step 3: Implement `client/become-builder/page.tsx`**

```tsx
"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { RoleGuard } from "@/components/RoleGuard";
import { useAuth } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { Button, Card } from "@/components/ui";

function BecomeBuilderInner() {
  const { becomeBuilder } = useAuth();
  const router = useRouter();
  const [accepted, setAccepted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onUpgrade() {
    setError(null);
    setBusy(true);
    try {
      await becomeBuilder();
      router.replace("/builder");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Upgrade failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl px-5 py-12">
      <p className="eyebrow flex items-center gap-2">
        <span className="inline-block h-px w-6 bg-accent" />
        Upgrade
      </p>
      <h1 className="mt-3 text-3xl font-extrabold tracking-tight">Become a builder</h1>
      <p className="mt-2 text-sm text-muted">
        Register AI agents, run a storefront, and earn credits on accepted work. Your client account
        and wallet stay exactly as they are — you gain the builder surface on top.
      </p>

      <Card className="mt-6 p-6">
        <label className="flex items-start gap-3 text-sm text-fg">
          <input
            type="checkbox"
            aria-label="accept builder terms"
            checked={accepted}
            onChange={(e) => setAccepted(e.target.checked)}
            className="mt-1"
          />
          <span>
            I agree to the builder terms: my agents must honour their declared output spec, and
            payouts are settled per the platform’s escrow rules.
          </span>
        </label>

        {error && (
          <p role="alert" className="mt-4 rounded-md border border-red/30 bg-red/10 px-3 py-2 font-mono text-xs text-red">
            {error}
          </p>
        )}

        <Button onClick={onUpgrade} disabled={!accepted || busy} className="mt-6 w-full">
          {busy ? "Upgrading…" : "Become a builder ▸"}
        </Button>
      </Card>
    </div>
  );
}

export default function BecomeBuilderPage() {
  return (
    <RoleGuard role="CLIENT">
      <BecomeBuilderInner />
    </RoleGuard>
  );
}
```

- [ ] **Step 4: Run to verify GREEN**

Run: `npx vitest run test/becomeBuilder.test.tsx`
Expected: PASS.

> If `Card` is not exported from `@/components/ui`, import it from `@/components/ui/Card` instead — check the barrel before running.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/client/become-builder/page.tsx frontend/test/becomeBuilder.test.tsx
git commit -m "feat(fe): become-a-builder page (accept terms → upgrade → /builder)"
```

### Task 5.8: Role guards use `hasRole`; Nav surface switcher

**Files:**
- Modify: `frontend/components/RequireAuth.tsx`
- Modify: `frontend/components/RoleGuard.tsx`
- Modify: `frontend/components/Nav.tsx`

- [ ] **Step 1: `RequireAuth` — gate on capability, not the active surface.** Replace lines 19–36:

```tsx
export function RequireAuth({ children, role }: RequireAuthProps) {
  const { token, hasRole } = useAuth();
  const router = useRouter();

  const allowed = !!token && (!role || hasRole(role));

  useEffect(() => {
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem("hireai.token");
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && token && !hasRole(role)) router.replace("/login");
  }, [token, role, hasRole, router]);

  return allowed ? <>{children}</> : null;
}
```

- [ ] **Step 2: `RoleGuard` — same.** Replace lines 21–37:

```tsx
  const { token, hasRole, activeSurface } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (typeof window === "undefined") return;
    const persisted = localStorage.getItem(TOKEN_KEY);
    if (!persisted) {
      router.replace("/login");
      return;
    }
    if (role && token && !hasRole(role)) {
      router.replace(hasRole("CLIENT") ? "/client" : "/builder");
    }
  }, [token, role, hasRole, router]);

  if (!token || (role && !hasRole(role))) return null;
  return <>{children}</>;
```

> `activeSurface` is destructured for future use by the switcher; if your linter flags it as unused here, drop it from this file — it's only needed in `Nav`.

- [ ] **Step 3: `Nav` — add the Client/Builder switcher for dual-capability users.** Replace the `useAuth()` destructure (line 26) and `home` (line 27):

```tsx
  const { roles, role, activeSurface, setActiveSurface, hasRole, logout } = useAuth();
  const dual = hasRole("CLIENT") && hasRole("BUILDER");
  const home = activeSurface === "BUILDER" ? "/builder" : "/client";
```

Then change the nav-section conditionals to key off `activeSurface` instead of `role` (replace `role === "CLIENT"` → `activeSurface === "CLIENT"` and `role === "BUILDER"` → `activeSurface === "BUILDER"` at lines 36 and 52). Add the switcher just before the `online` chip (before line 68):

```tsx
            {dual && (
              <div className="hidden items-center rounded-md border border-line bg-surface-2 p-0.5 md:flex">
                {(["CLIENT", "BUILDER"] as const).map((r) => (
                  <button
                    key={r}
                    type="button"
                    onClick={() => setActiveSurface(r)}
                    className={`rounded px-2.5 py-1 font-mono text-[0.6rem] uppercase tracking-[0.18em] transition ${
                      activeSurface === r ? "bg-accent/15 text-accent" : "text-muted hover:text-fg"
                    }`}
                  >
                    {r}
                  </button>
                ))}
              </div>
            )}
```

- [ ] **Step 4: Run the full frontend suite** (the 14 page tests + RequireAuth + new tests)

Run: `npx vitest run`
Expected: all PASS. (`RequireAuth.test.tsx` legacy `{role:"CLIENT"}` sessions normalize to `roles:["CLIENT"]` → `hasRole` works.)

- [ ] **Step 5: Commit**

```bash
git add frontend/components/RequireAuth.tsx frontend/components/RoleGuard.tsx frontend/components/Nav.tsx
git commit -m "feat(fe): role guards use hasRole; Nav client/builder surface switcher"
```

### Task 5.9: Frontend env for the Google button

**Files:**
- Modify: `frontend/.env.example`

- [ ] **Step 1: Document the new var**

```
# Backend origin the /api/* proxy forwards to (see next.config.ts).
BACKEND_URL=http://localhost:8080

# Public backend origin the browser navigates to for the Google OAuth handshake
# (the "Continue with Google" button). Must be reachable from the browser.
NEXT_PUBLIC_API_ORIGIN=http://localhost:8080
```

- [ ] **Step 2: Build the frontend**

Run: `npm --prefix frontend run build`
Expected: build succeeds (new routes `/register`, `/auth/callback`, `/client/become-builder` compile).

- [ ] **Step 3: Commit**

```bash
git add frontend/.env.example
git commit -m "docs(fe): NEXT_PUBLIC_API_ORIGIN for the Google OAuth button"
```

# Phase 6 — Full verification + docs

### Task 6.1: Full suites green

- [ ] **Step 1: Backend full suite**

Run: `mvn -f backend/pom.xml -B test`
Expected: all green. Note in output whether Testcontainers integration tests ran (Docker present) or auto-skipped.

- [ ] **Step 2: Frontend full suite**

Run (in `frontend/`): `npx vitest run`
Expected: all green — the ~50 existing tests plus the new `jwt`, `register`, `callback`, `becomeBuilder` tests and the updated `auth` tests.

- [ ] **Step 3: Frontend production build**

Run: `npm --prefix frontend run build`
Expected: success.

### Task 6.2: Manual E2E checklist (Google stubbed)

Stand up the stack per `docs/details/demo-runbook.md` (Postgres + RabbitMQ + backend + frontend). Real Google OAuth needs live `GOOGLE_CLIENT_ID/SECRET` + `OAUTH2_ENABLED=true` and a redirect URI `http://localhost:8080/login/oauth2/code/google` registered in Google Cloud — **drive it manually if creds are available; otherwise verify the email/password + become-builder paths and record that the Google round-trip was not driven** (do not claim it passed).

- [ ] **Step 1:** Register a new client at `/register` (email/password) → lands on `/client`; confirm a wallet exists (`GET /api/wallet` returns a zero-balance wallet).
- [ ] **Step 2:** Log out, log back in with the same credentials → succeeds with `roles:["CLIENT"]`.
- [ ] **Step 3:** From `/client`, go to `/client/become-builder`, accept terms, upgrade → lands on `/builder`; the Nav now shows the Client/Builder switcher; register an agent.
- [ ] **Step 4:** Toggle the switcher → routes between `/client` and `/builder`; both surfaces render.
- [ ] **Step 5 (if Google creds present):** Click "Continue with Google", complete consent → returns to `/auth/callback`, token stored, lands on `/client`; a second Google login with the same account reuses the same user; if the Google email matches an existing password account, it links (one account).

### Task 6.3: Update docs

**Files:**
- Modify: `docs/details/identity-and-authz.md`
- Modify: `docs/details/frontend.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1:** In `docs/details/identity-and-authz.md`, document: the `user_roles` RBAC model and the `roles` JWT claim; `POST /api/auth/register` (creates CLIENT + wallet); the Google OAuth chain (flag-gated, mints our JWT, fragment hand-off, account linking by verified email); `POST /api/auth/become-builder`; the new env vars (`OAUTH2_ENABLED`, `GOOGLE_CLIENT_ID/SECRET`, `OAUTH2_SUCCESS_REDIRECT_URL`, `OAUTH2_FAILURE_REDIRECT_URL`).
- [ ] **Step 2:** In `docs/details/frontend.md`, document: `/register`, `/auth/callback`, `/client/become-builder`; the auth context's `roles`/`hasRole`/`activeSurface` + switcher; `decodeJwt`; `NEXT_PUBLIC_API_ORIGIN`.
- [ ] **Step 3:** In `CLAUDE.md`, update the backend/frontend status lines: the thin JWT slice is now full self-registration + Google OAuth + dual-capability RBAC (`user_roles`, `user_identities`, `display_name`, `V10`–`V12`); update the test counts after the run; mention the become-builder upgrade.
- [ ] **Step 4: Commit**

```bash
git add docs/details/identity-and-authz.md docs/details/frontend.md CLAUDE.md
git commit -m "docs: registration + Google OAuth + dual-capability RBAC"
```

### Task 6.4: Branch wrap-up

- [ ] **Step 1:** Confirm the working tree is clean (`git status`) and the branch builds + tests green end-to-end.
- [ ] **Step 2:** Use the **superpowers:finishing-a-development-branch** skill to decide merge/PR/cleanup.

---

## Spec coverage map

| Spec section | Plan task(s) |
|---|---|
| §2 D1 Google provider | 3.1 (config), 3.4 (handler), 5.4/5.5 (buttons) |
| §2 D2/D3 dual-capability `user_roles` | 1.1 (V10), 1.4 (model/persistence), 4.* (become-builder) |
| §2 D4 backend-driven OAuth mints our JWT | 3.3 (resolution), 3.4 (handler), 3.5 (chain) |
| §2 D5 link by verified email | 3.3 (resolveByEmailOrCreate + email_verified) |
| §2 D6 fragment hand-off | 3.4 (handler redirect), 5.6 (callback page) |
| §2 D7 wallet at registration | 2.2 (register), 3.3 (OAuth create) |
| §4 V10–V12 schema | 1.1 |
| §5.1 role-set refactor | 1.2–1.5 |
| §5.2 register endpoint | 2.1–2.3 |
| §5.3 OAuth (client/handler/chain) | 3.1–3.5 |
| §5.4 become-builder | 4.1–4.2 |
| §5.5 security config | 2.3 (register permit), 3.5 (oauth chain) |
| §6 fragment hand-off | 3.4, 5.6 |
| §7 frontend (register/callback/become-builder/switcher/context/guards) | 5.1–5.9 |
| §8 error handling | 2.1 (409), 2.3 (400), 3.3/3.4 (unverified→failure) |
| §9 testing (unit/integration/fe/e2e) | TDD throughout; 6.1–6.2 |
| §10 sequencing | Phases 1→6 |

## Notes / deviations from the spec

- **Migration drop timing.** The spec's §4 wrote `users.role`'s drop into V10; the plan keeps it in V10 (V10 backfills then drops in one migration). Because `ddl-auto: validate` + Flyway-applies-all couples schema to entities, the role refactor (Task 1.2–1.5) lands in the **same phase** as the migrations — its green checkpoint is the phase end (Task 1.9), not per-edit. Pure unit tests still run per-step.
- **OAuth feature flag.** Added `hireai.auth.oauth2.enabled` (default false) so the default/dev/test contexts start without Google creds and the existing ~345 tests are unaffected; not in the spec but required for clean startup.
- **`activeSurface`.** The spec's "switcher" is implemented as a persisted `activeSurface` (localStorage `hireai.surface`) driving Nav + home routing; guards gate on `hasRole` (capability), so a dual user can reach both surfaces regardless of the active one.
