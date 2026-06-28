# Capability Re-division — Slice 1: Identity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Relocate the `user` + `auth` code into a single `identity` subdomain across every module, and deepen the User aggregate with a `Credential` value object, an `OAuthIdentity` domain entity, and a `grant(Role)` domain behavior — keeping the whole suite green.

**Architecture:** Slice 1 of the incremental-strangler refactor in `docs/superpowers/specs/2026-06-29-backend-capability-redivision-design.md`. Pure relocation first (behavior-identical, suite green), then three small TDD deepenings. No migration — the new shapes sit over existing columns/tables. Controllers stay route-grouped, so `/api/auth/*` and the frontend are untouched.

**Tech Stack:** Java 21, Spring Boot 3.x, COLA multi-module Maven reactor, JUnit 5 + Mockito + AssertJ, Testcontainers (auto-skip without Docker).

## Global Constraints

- **Suite green at every commit.** Run `mvn -f backend/pom.xml -B test` after each task; it must pass (compile + unit). Run with Docker up at the slice's end so the Testcontainers `*IntegrationTest`s actually execute rather than auto-skip.
- **COLA layering is compiler-enforced.** `hireai-domain` and `hireai-utility` carry zero Spring. Dependencies point inward only; a wrong-way import won't resolve. Domain code imports nothing from `application`/`infrastructure`/`controller`.
- **Naming suffixes (apply exactly):** `XxxModel` (aggregate/entity/VO in domain), `XxxDO` (JPA entity in `hireai-repository`), `XxxRepository` (interface in domain) / `XxxRepositoryImpl` (infra). Value objects are immutable records.
- **No migration in this slice.** `Credential` and `OAuthIdentity` map onto existing `users.password_hash` and the `user_identities` table.
- **Routes unchanged.** `controller.biz.auth` (AuthController) and `controller.config` (security/OAuth) stay where they are; only their imports of moved packages change.
- **Attribution disabled** in commit messages (per the repo's global git setting) — no `Co-Authored-By` trailer.

## File Structure

After this slice, the Identity subdomain owns these packages (moved + new):

```
hireai-domain      com.hireai.domain.biz.identity
                     model/      UserModel, Credential (NEW), OAuthIdentity (NEW)
                     enums/      Role
                     repository/ UserRepository, OAuthIdentityRepository (renamed from UserIdentityRepository)
                     service/    OAuthAccountLinkingDomainService (+ impl/)
hireai-application com.hireai.application.biz.identity
                     AuthAppService(+impl), OAuthAppService(+impl),
                     LoginInfo, RegisterInfo, OAuthUserInfo, AuthResult
hireai-repository  com.hireai.infrastructure.repository.identity
                     UserDO, UserRoleDO, UserIdentityDO, *JpaRepository,
                     UserRepositoryImpl, OAuthIdentityRepositoryImpl (renamed)
```

Unchanged locations (imports update only): `controller.biz.auth.*`, `controller.config.*` (OAuth2 handler, security), `application.config.DomainServiceConfig`, `application.port.security.*`, `infrastructure.security.impl.JjwtService`.

---

### Task 1: Relocate `user` + `auth` → `identity` (mechanical, behavior-identical)

Move three package trees and rewrite their fully-qualified prefixes everywhere. This is one atomic compile-green unit — all moves land together (a half-moved tree won't compile). No behavior change, so the existing suite is the test.

**Files:** moves only — three source prefixes + their test mirrors:
- `com.hireai.domain.biz.user` → `com.hireai.domain.biz.identity` (`hireai-domain` main + `hireai-main` test mirror `domain/biz/user`)
- `com.hireai.application.biz.auth` → `com.hireai.application.biz.identity` (`hireai-application` main + test mirror `application/biz/auth`)
- `com.hireai.infrastructure.repository.user` → `com.hireai.infrastructure.repository.identity` (`hireai-repository` main)
- Test-only package `com.hireai.user` (`UserRepositoryIntegrationTest`) → `com.hireai.identity`

**Interfaces:**
- Consumes: nothing new.
- Produces: the relocated FQNs above. Every later task and slice imports `com.hireai.domain.biz.identity.*`, `com.hireai.application.biz.identity.*`, `com.hireai.infrastructure.repository.identity.*`.

- [ ] **Step 1: Move the directories (git mv preserves history)**

Run (Git Bash, from repo root):

```bash
cd backend
# domain
git mv hireai-domain/src/main/java/com/hireai/domain/biz/user hireai-domain/src/main/java/com/hireai/domain/biz/identity
# application (app services + carriers)
git mv hireai-application/src/main/java/com/hireai/application/biz/auth hireai-application/src/main/java/com/hireai/application/biz/identity
# repository
git mv hireai-repository/src/main/java/com/hireai/infrastructure/repository/user hireai-repository/src/main/java/com/hireai/infrastructure/repository/identity
# test mirrors
git mv hireai-main/src/test/java/com/hireai/domain/biz/user hireai-main/src/test/java/com/hireai/domain/biz/identity
git mv hireai-main/src/test/java/com/hireai/application/biz/auth hireai-main/src/test/java/com/hireai/application/biz/identity
git mv hireai-main/src/test/java/com/hireai/user hireai-main/src/test/java/com/hireai/identity
```

- [ ] **Step 2: Rewrite the three package prefixes across all Java files**

Run (Git Bash, from repo root) — the three prefixes are non-overlapping and do **not** touch `controller.biz.auth`:

```bash
grep -rl --include='*.java' -e 'com\.hireai\.domain\.biz\.user' -e 'com\.hireai\.application\.biz\.auth' -e 'com\.hireai\.infrastructure\.repository\.user' backend \
  | xargs sed -i \
      -e 's/com\.hireai\.domain\.biz\.user/com.hireai.domain.biz.identity/g' \
      -e 's/com\.hireai\.application\.biz\.auth/com.hireai.application.biz.identity/g' \
      -e 's/com\.hireai\.infrastructure\.repository\.user/com.hireai.infrastructure.repository.identity/g'
# Fix the test-only package com.hireai.user -> com.hireai.identity (only the moved integration test declares it)
sed -i 's/^package com\.hireai\.user;/package com.hireai.identity;/' backend/hireai-main/src/test/java/com/hireai/identity/UserRepositoryIntegrationTest.java
```

- [ ] **Step 3: Verify no stale references remain**

Run:

```bash
grep -rn --include='*.java' -e 'com\.hireai\.domain\.biz\.user' -e 'com\.hireai\.application\.biz\.auth' -e 'com\.hireai\.infrastructure\.repository\.user' backend
```

Expected: **no output** (every occurrence rewritten). `controller.biz.auth` references must still be present and unchanged — confirm with `grep -rn 'controller\.biz\.auth' backend` (these stay).

- [ ] **Step 4: Build + test (suite must stay green)**

Run:

```bash
mvn -f backend/pom.xml -q -B test
```

Expected: BUILD SUCCESS, same test count as before the move, zero failures. (Compilation proves every import was rewritten; the layer modules would fail a wrong-way import.)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(identity): relocate user+auth packages into the identity subdomain"
```

---

### Task 2: Introduce the `Credential` value object on `UserModel`

Replace the bare `String passwordHash` field on `UserModel` with a `Credential` VO that makes "OAuth-only, no local password" explicit (`Credential.NONE`) instead of a magic `null`.

**Files:**
- Create: `hireai-domain/src/main/java/com/hireai/domain/biz/identity/model/Credential.java`
- Create test: `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/CredentialTest.java`
- Modify: `hireai-domain/.../identity/model/UserModel.java` (field + factory)
- Modify: `hireai-application/.../identity/impl/AuthAppServiceImpl.java` (register, login)
- Modify: `hireai-application/.../identity/impl/OAuthAppServiceImpl.java` (`resolveByEmailOrCreate`)
- Modify: `hireai-repository/.../identity/UserRepositoryImpl.java` (`create`, `toModel`)
- Modify tests: `application/biz/identity/impl/{AuthAppServiceImplTest, AuthAppServiceRegisterTest, AuthAppServiceBecomeBuilderTest, OAuthAppServiceImplTest}.java` (UserModel construction + `.passwordHash()` accessors)

**Interfaces:**
- Consumes: `UserModel` (relocated).
- Produces:
  - `Credential` (record): `Credential.ofHash(String) -> Credential`, `Credential.NONE`, `credential.secretHash() -> String`, `credential.isAbsent() -> boolean`.
  - `UserModel(UUID id, String email, Credential credential, String displayName, Set<Role> roles, boolean active)`; `UserModel.newClient(String email, Credential credential, String displayName) -> UserModel`; accessor `credential()`.

- [ ] **Step 1: Write the failing test for `Credential`**

Create `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/CredentialTest.java`:

```java
package com.hireai.domain.biz.identity.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTest {

    @Test
    void ofHashWrapsAnExistingHash() {
        Credential c = Credential.ofHash("$2a$bcrypt");
        assertThat(c.secretHash()).isEqualTo("$2a$bcrypt");
        assertThat(c.isAbsent()).isFalse();
    }

    @Test
    void ofHashNullIsTheNoneCredential() {
        assertThat(Credential.ofHash(null)).isEqualTo(Credential.NONE);
        assertThat(Credential.ofHash(null).isAbsent()).isTrue();
    }

    @Test
    void noneHasNoSecretAndIsAbsent() {
        assertThat(Credential.NONE.secretHash()).isNull();
        assertThat(Credential.NONE.isAbsent()).isTrue();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=CredentialTest`
Expected: FAIL — `Credential` does not exist (compile error).

- [ ] **Step 3: Create `Credential`**

Create `hireai-domain/src/main/java/com/hireai/domain/biz/identity/model/Credential.java`:

```java
package com.hireai.domain.biz.identity.model;

/**
 * A user's local password credential — the BCrypt hash, or {@link #NONE} for an OAuth-only account
 * with no local password. Value object: immutable, equality by value. Verifying a raw password needs
 * the PasswordEncoder and is done in the application layer, not here.
 */
public record Credential(String secretHash) {

    /** No local password (an OAuth-only account). */
    public static final Credential NONE = new Credential(null);

    public static Credential ofHash(String secretHash) {
        return secretHash == null ? NONE : new Credential(secretHash);
    }

    public boolean isAbsent() {
        return secretHash == null;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=CredentialTest`
Expected: PASS.

- [ ] **Step 5: Migrate `UserModel` to hold a `Credential`**

Replace `hireai-domain/src/main/java/com/hireai/domain/biz/identity/model/UserModel.java` with:

```java
package com.hireai.domain.biz.identity.model;

import com.hireai.domain.biz.identity.enums.Role;

import java.util.Set;
import java.util.UUID;

/**
 * User aggregate (read + create). Dual-capability: every user holds {@code CLIENT} and may add
 * {@code BUILDER}. Roles are sourced from the {@code user_roles} join table. Immutable.
 */
public record UserModel(UUID id, String email, Credential credential, String displayName,
                        Set<Role> roles, boolean active) {

    /** A brand-new self-serve account: random id, CLIENT role, active. */
    public static UserModel newClient(String email, Credential credential, String displayName) {
        return new UserModel(UUID.randomUUID(), email, credential, displayName, Set.of(Role.CLIENT), true);
    }
}
```

- [ ] **Step 6: Update the two app services to the `Credential` shape**

In `hireai-application/.../identity/impl/AuthAppServiceImpl.java`:
- Add import: `import com.hireai.domain.biz.identity.model.Credential;`
- In `register(...)`, change the `UserModel.newClient` call:

```java
        UserModel user = userRepository.create(
                UserModel.newClient(info.email(), Credential.ofHash(hash), info.displayName()));
```

- In `login(...)`, replace the password check:

```java
        if (user.credential().isAbsent()
                || !passwordEncoder.matches(loginInfo.password(), user.credential().secretHash())) {
            throw new AuthenticationFailedException();
        }
```

In `hireai-application/.../identity/impl/OAuthAppServiceImpl.java`:
- Add import: `import com.hireai.domain.biz.identity.model.Credential;`
- In `resolveByEmailOrCreate(...)`, change the create call:

```java
        UserModel created = userRepository.create(
                UserModel.newClient(info.email(), Credential.NONE, info.displayName()));
```

- [ ] **Step 7: Update `UserRepositoryImpl` mapping**

In `hireai-repository/.../identity/UserRepositoryImpl.java`:
- Add import: `import com.hireai.domain.biz.identity.model.Credential;`
- In `create(...)`, map the hash out of the credential:

```java
        userJpa.save(new UserDO(user.id(), user.email(), user.credential().secretHash(),
                user.displayName(), user.active()));
```

- In `toModel(...)`, wrap the stored hash:

```java
        return new UserModel(e.getId(), e.getEmail(), Credential.ofHash(e.getPasswordHash()),
                e.getDisplayName(), roles, e.isActive());
```

(`UserDO` is unchanged — it still stores `password_hash` as a `String`.)

- [ ] **Step 8: Update the rippled unit tests**

In each of `application/biz/identity/impl/{AuthAppServiceImplTest, AuthAppServiceRegisterTest, AuthAppServiceBecomeBuilderTest, OAuthAppServiceImplTest}.java`, add `import com.hireai.domain.biz.identity.model.Credential;` and:
- Replace every `new UserModel(id, email, "h", display, roles, active)` with `new UserModel(id, email, Credential.ofHash("h"), display, roles, active)`, and every `null` password-hash argument with `Credential.NONE`.
- Replace every accessor `.passwordHash()` assertion. In `OAuthAppServiceImplTest.createsNewClientWithWalletAndLinkWhenUnknown`, change:

```java
        assertThat(userCaptor.getValue().credential().isAbsent()).isTrue();
```

- For any test that builds a hashed user (e.g. login tests passing a BCrypt hash), use `Credential.ofHash(<hash>)`.

- [ ] **Step 9: Run the full suite to verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS, zero failures.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(identity): model the password hash as a Credential value object"
```

---

### Task 3: Introduce the `OAuthIdentity` domain entity

Give the OAuth link a domain model owned by the User aggregate, and have the repository speak `OAuthIdentity` instead of loose `link(...)` parameters. Rename the repository interface to `OAuthIdentityRepository` (persistence DO/JPA keep their `UserIdentity*` names — they map the `user_identities` table).

**Files:**
- Create: `hireai-domain/.../identity/model/OAuthIdentity.java`
- Create test: `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/OAuthIdentityTest.java`
- Rename: `hireai-domain/.../identity/repository/UserIdentityRepository.java` → `OAuthIdentityRepository.java` (method `link` → `save(OAuthIdentity)`)
- Rename: `hireai-repository/.../identity/UserIdentityRepositoryImpl.java` → `OAuthIdentityRepositoryImpl.java`
- Modify: `hireai-application/.../identity/impl/OAuthAppServiceImpl.java` (field type + save call)
- Modify test: `application/biz/identity/impl/OAuthAppServiceImplTest.java` (mock type + verifications)

**Interfaces:**
- Consumes: `UserModel`, `Credential` (Task 2).
- Produces:
  - `OAuthIdentity` (record): `OAuthIdentity.link(UUID userId, String provider, String subject, String emailAtLink) -> OAuthIdentity`; accessors `id()`, `userId()`, `provider()`, `subject()`, `emailAtLink()`.
  - `OAuthIdentityRepository`: `Optional<UUID> findUserIdByProviderSubject(String provider, String subject)`, `void save(OAuthIdentity identity)`.

- [ ] **Step 1: Write the failing test for `OAuthIdentity`**

Create `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/OAuthIdentityTest.java`:

```java
package com.hireai.domain.biz.identity.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthIdentityTest {

    @Test
    void linkMintsAnIdentityWithAFreshIdForTheUser() {
        UUID userId = UUID.randomUUID();
        OAuthIdentity identity = OAuthIdentity.link(userId, "google", "sub-123", "ada@hireai.local");

        assertThat(identity.id()).isNotNull();
        assertThat(identity.userId()).isEqualTo(userId);
        assertThat(identity.provider()).isEqualTo("google");
        assertThat(identity.subject()).isEqualTo("sub-123");
        assertThat(identity.emailAtLink()).isEqualTo("ada@hireai.local");
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=OAuthIdentityTest`
Expected: FAIL — `OAuthIdentity` does not exist.

- [ ] **Step 3: Create `OAuthIdentity`**

Create `hireai-domain/src/main/java/com/hireai/domain/biz/identity/model/OAuthIdentity.java`:

```java
package com.hireai.domain.biz.identity.model;

import java.util.UUID;

/**
 * An external identity link (OAuth) owned by the User aggregate: a provider plus its stable subject.
 * Immutable. {@code emailAtLink} records the provider email captured when the link was made (audit only).
 */
public record OAuthIdentity(UUID id, UUID userId, String provider, String subject, String emailAtLink) {

    /** Mint a new link for an existing local user. */
    public static OAuthIdentity link(UUID userId, String provider, String subject, String emailAtLink) {
        return new OAuthIdentity(UUID.randomUUID(), userId, provider, subject, emailAtLink);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=OAuthIdentityTest`
Expected: PASS.

- [ ] **Step 5: Rename the repository interface and reshape its write method**

Rename the file and replace its contents — `hireai-domain/src/main/java/com/hireai/domain/biz/identity/repository/OAuthIdentityRepository.java`:

```java
package com.hireai.domain.biz.identity.repository;

import com.hireai.domain.biz.identity.model.OAuthIdentity;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for external identity links (OAuth) owned by the User aggregate.
 * One repository per table per the DDD conventions.
 */
public interface OAuthIdentityRepository {

    /** The local user id linked to a provider's stable subject, if any. */
    Optional<UUID> findUserIdByProviderSubject(String provider, String subject);

    /** Persists a new identity link. */
    void save(OAuthIdentity identity);
}
```

```bash
git rm backend/hireai-domain/src/main/java/com/hireai/domain/biz/identity/repository/UserIdentityRepository.java
```

- [ ] **Step 6: Rename + update the repository impl**

Rename the file to `hireai-repository/src/main/java/com/hireai/infrastructure/repository/identity/OAuthIdentityRepositoryImpl.java` with contents:

```java
package com.hireai.infrastructure.repository.identity;

import com.hireai.domain.biz.identity.model.OAuthIdentity;
import com.hireai.domain.biz.identity.repository.OAuthIdentityRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link OAuthIdentityRepository}. Maps onto the user_identities table. */
@Repository
public class OAuthIdentityRepositoryImpl implements OAuthIdentityRepository {

    private final UserIdentityJpaRepository jpa;

    public OAuthIdentityRepositoryImpl(UserIdentityJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<UUID> findUserIdByProviderSubject(String provider, String subject) {
        return jpa.findByProviderAndProviderSubject(provider, subject)
                .map(UserIdentityDO::getUserId);
    }

    @Override
    public void save(OAuthIdentity identity) {
        jpa.save(new UserIdentityDO(identity.id(), identity.userId(), identity.provider(),
                identity.subject(), identity.emailAtLink()));
    }
}
```

```bash
git rm backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/identity/UserIdentityRepositoryImpl.java
```

(`UserIdentityDO` and `UserIdentityJpaRepository` keep their names and packages — they map the `user_identities` table.)

- [ ] **Step 7: Update `OAuthAppServiceImpl`**

In `hireai-application/.../identity/impl/OAuthAppServiceImpl.java`:
- Replace import `com.hireai.domain.biz.identity.repository.UserIdentityRepository` with `com.hireai.domain.biz.identity.repository.OAuthIdentityRepository`, and add `import com.hireai.domain.biz.identity.model.OAuthIdentity;`
- Change the field + constructor parameter type from `UserIdentityRepository` to `OAuthIdentityRepository` (keep the name `identityRepository`).
- In `resolveByEmailOrCreate(...)`, replace the link call:

```java
        identityRepository.save(
                OAuthIdentity.link(created.id(), info.provider(), info.subject(), info.email()));
```

- [ ] **Step 8: Update `OAuthAppServiceImplTest`**

In `application/biz/identity/impl/OAuthAppServiceImplTest.java`:
- Replace import + mock type `UserIdentityRepository` → `OAuthIdentityRepository`; add `import com.hireai.domain.biz.identity.model.OAuthIdentity;`
- Replace the two `never().link(...)` verifications with `verify(identityRepository, never()).save(any());`
- Replace the positive link verification in `createsNewClientWithWalletAndLinkWhenUnknown` with:

```java
        ArgumentCaptor<OAuthIdentity> identityCaptor = ArgumentCaptor.forClass(OAuthIdentity.class);
        verify(identityRepository).save(identityCaptor.capture());
        assertThat(identityCaptor.getValue().userId()).isEqualTo(userCaptor.getValue().id());
        assertThat(identityCaptor.getValue().provider()).isEqualTo("google");
        assertThat(identityCaptor.getValue().subject()).isEqualTo("sub-123");
        assertThat(identityCaptor.getValue().emailAtLink()).isEqualTo("ada@hireai.local");
```

- [ ] **Step 9: Run the full suite to verify green**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS, zero failures.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(identity): model OAuth links as an OAuthIdentity domain entity"
```

---

### Task 4: Add `UserModel.grant(Role)` domain behavior for become-builder

Move the role-set mutation into the aggregate: `becomeBuilder` derives the new role set from a domain method instead of re-reading the user after the grant.

**Files:**
- Modify: `hireai-domain/.../identity/model/UserModel.java` (add `grant`)
- Create test: `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/UserModelGrantTest.java`
- Modify: `hireai-application/.../identity/impl/AuthAppServiceImpl.java` (`becomeBuilder`)

**Interfaces:**
- Consumes: `UserModel`, `Role`, `UserRepository.addRole`.
- Produces: `UserModel.grant(Role role) -> UserModel` (a copy whose `roles` includes `role`; idempotent).

- [ ] **Step 1: Write the failing test**

Create `hireai-main/src/test/java/com/hireai/domain/biz/identity/model/UserModelGrantTest.java`:

```java
package com.hireai.domain.biz.identity.model;

import com.hireai.domain.biz.identity.enums.Role;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserModelGrantTest {

    private UserModel client() {
        return new UserModel(UUID.randomUUID(), "ada@hireai.local", Credential.ofHash("h"),
                "Ada", Set.of(Role.CLIENT), true);
    }

    @Test
    void grantAddsTheRole() {
        UserModel upgraded = client().grant(Role.BUILDER);
        assertThat(upgraded.roles()).containsExactlyInAnyOrder(Role.CLIENT, Role.BUILDER);
    }

    @Test
    void grantIsIdempotentAndDoesNotMutateTheOriginal() {
        UserModel original = client();
        UserModel twice = original.grant(Role.BUILDER).grant(Role.BUILDER);
        assertThat(twice.roles()).containsExactlyInAnyOrder(Role.CLIENT, Role.BUILDER);
        assertThat(original.roles()).containsExactly(Role.CLIENT);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=UserModelGrantTest`
Expected: FAIL — `grant` is not defined.

- [ ] **Step 3: Add `grant` to `UserModel`**

In `hireai-domain/.../identity/model/UserModel.java`, add `import java.util.EnumSet;` and the method:

```java
    /** A copy of this user with {@code role} added (idempotent). */
    public UserModel grant(Role role) {
        EnumSet<Role> next = EnumSet.copyOf(roles);
        next.add(role);
        return new UserModel(id, email, credential, displayName, Set.copyOf(next), active);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -q -B -pl hireai-main -am test -Dtest=UserModelGrantTest`
Expected: PASS.

- [ ] **Step 5: Use the domain behavior in `becomeBuilder`**

Replace the body of `becomeBuilder` in `hireai-application/.../identity/impl/AuthAppServiceImpl.java`:

```java
    @Override
    @Transactional
    public AuthResult becomeBuilder(UUID userId) {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + userId));
        UserModel upgraded = user.grant(Role.BUILDER);
        userRepository.addRole(userId, Role.BUILDER);

        List<String> roles = upgraded.roles().stream().map(Role::name).sorted().toList();
        String token = jwtService.issue(userId, roles, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} upgraded to builder (roles {})", userId, roles);
        return new AuthResult(token, userId, roles);
    }
```

This drops the second `findById` re-read while keeping the idempotent persisted grant.

- [ ] **Step 6: Run the full suite (incl. `AuthAppServiceBecomeBuilderTest`)**

Run: `mvn -f backend/pom.xml -q -B test`
Expected: BUILD SUCCESS, zero failures. If `AuthAppServiceBecomeBuilderTest` stubbed the second `findById` to return the builder-roled user, simplify it to assert the token roles come from the granted set (one `findById` stub).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(identity): grant builder role via a UserModel domain behavior"
```

---

### Task 5: Slice verification with Docker (integration tests actually run)

The unit suite stays green per task; this gate runs the Testcontainers integration tests so the relocation/deepening is proven against a real Postgres rather than auto-skipped.

**Files:** none (verification only).

- [ ] **Step 1: Ensure Docker is running**

Run: `docker info`
Expected: prints daemon info (not "Cannot connect to the Docker daemon"). If Docker is unavailable, state that explicitly — do not claim green.

- [ ] **Step 2: Run the full suite with integration tests active**

Run: `mvn -f backend/pom.xml -B test`
Expected: BUILD SUCCESS. Confirm `AuthIntegrationTest` and `UserRepositoryIntegrationTest` (now in `com.hireai.identity`) **ran** (not skipped) and passed — the console should show them executing, with Testcontainers starting Postgres.

- [ ] **Step 3: Tag the slice**

```bash
git tag redivision-1-identity
```

---

## Self-Review

**Spec coverage (Identity rows of spec §4 + §6 step 1):**
- "`Credential` VO" → Task 2. ✓
- "`OAuthIdentity` domain entity owned by the root, mapped onto `user_identities`" → Task 3. ✓
- "`becomeBuilder`'s role mutation becomes a domain behavior on `User`" → Task 4. ✓
- "No migration" → confirmed; `UserDO`/`UserIdentityDO` and the `users`/`user_identities` tables are untouched. ✓
- "relocate `user`+`auth` → `identity`" + "establishes the subdomain-package pattern" → Task 1. ✓
- "run with Docker up at each milestone" (spec §2 verification) → Task 5. ✓
- Routes stable (controllers stay) → Task 1 keeps `controller.biz.auth`; verified by the negative grep in Task 1 Step 3. ✓

**Placeholder scan:** no TBD/TODO; every code step shows complete code or an exact command. ✓

**Type consistency:** `Credential.ofHash`/`NONE`/`secretHash()`/`isAbsent()` used identically in Tasks 2–4; `OAuthIdentity.link(...)` and `OAuthIdentityRepository.save(...)` consistent across domain, impl, app service, and test; `UserModel.grant(Role)` signature matches its test and `becomeBuilder` use. ✓

**Note for the executor:** the exact set of `new UserModel(...)` constructions and `.passwordHash()` accessors to update in Task 2 Step 8 lives in `AuthAppServiceImplTest`, `AuthAppServiceRegisterTest`, `AuthAppServiceBecomeBuilderTest`, and `OAuthAppServiceImplTest`. Let the compiler list them: after editing `UserModel`, `mvn ... test-compile` flags every call site that still passes a `String` where a `Credential` is now required.
