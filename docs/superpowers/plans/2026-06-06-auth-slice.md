# Thin JWT Auth Slice Implementation Plan

**REQUIRED SUB-SKILL:** `agentic-workers:executing-plans` — execute this plan one task at a time, run the named test command after every step, and STOP at each checkpoint until the command output matches the stated expectation.

## Goal

Make hard invariant **#5 (server-side identity from JWT)** runtime-enforced. Add `POST /api/auth/login` (BCrypt check → HS256 JWT), a `JwtAuthenticationFilter` that derives the caller's identity from a verified token, and a `SecurityConfig` that **authenticates by default**. A `test` Spring profile bypasses auth (permitAll + the existing `DevCurrentUserProvider`) so the existing test suite stays green. The agent result callback stays authenticated by its dispatch token, never JWT (invariant #6, unchanged).

Login only — no register/refresh/reset. Two demo users (CLIENT + BUILDER) are seeded with BCrypt hashes + wallets via Flyway `V5`.

## Architecture

DDD layering already in place: `controller → application → domain ← infrastructure`; the domain is framework-free. This slice adds:

- **User read aggregate** (maps the existing `users` table, **no schema change**): `domain/biz/user/model/UserModel`, `domain/biz/user/enums/Role`, `domain/biz/user/repository/UserRepository`; infra `UserJpaEntity` + `UserRepositoryImpl`.
- **JWT port** `application/port/security/JwtService` (+ `JwtPrincipal`, `JwtInvalidException`) with infra impl `infrastructure/security/impl/JjwtService` — mirrors the existing `DispatchTokenService` / `HmacDispatchTokenService` port+impl style.
- **Auth application + controller**: `application/biz/auth/AuthAppService` (+ `impl/`), `controller/biz/auth/AuthController` with request/response DTOs and a `LoginInfo`/`AuthResult` carrier pair.
- **Security wiring**: `JwtAuthenticationFilter`, `JwtCurrentUserProvider` (`@Profile("!test")`), `DevCurrentUserProvider` flipped to `@Profile("test")`, and a two-chain `SecurityConfig` (one `SecurityFilterChain` bean per profile).
- **Flyway `V5__seed_demo_users.sql`** + a `BCryptPasswordEncoder` bean.

Identity swap is clean: every controller already calls `CurrentUserProvider.currentUserId()`, so **no controller changes** are needed. `JwtCurrentUserProvider` just reads the `SecurityContext` principal.

### Profile behaviour (decisions locked)

| Profile | SecurityFilterChain | CurrentUserProvider | Used by |
|---|---|---|---|
| default / `prod` / `dev` | JWT chain (`@Profile("!test")`): stateless, authenticate by default | `JwtCurrentUserProvider` (`@Profile("!test")`) | `mvn spring-boot:run` demo; `AuthIntegrationTest`; `RoutingIntegrationTest`'s `dev` is overridden to also include `test` (see Task 13) |
| `test` | permissive chain (`@Profile("test")`): permitAll | `DevCurrentUserProvider` (`@Profile("test")`) | all existing `@SpringBootTest` / `@WebMvcTest` tests |

Exactly one `SecurityFilterChain` bean and one `CurrentUserProvider` bean resolve per profile — no ambiguity.

## Tech Stack

- Java 21, Spring Boot 3.3.5, Spring Security (already a dependency), Spring Data JPA, Flyway, PostgreSQL.
- **NEW:** `io.jsonwebtoken:jjwt-api` / `jjwt-impl` / `jjwt-jackson` `0.12.6` (HS256 JWT issue/verify).
- `BCryptPasswordEncoder` (from `spring-security-crypto`, already on the classpath via `spring-boot-starter-security`).
- Tests: JUnit 5, `spring-security-test` (present), Testcontainers Postgres (present). Run with `mvn -f backend/pom.xml -B test`. Integration tests are named `*IntegrationTest`, gated by `@EnabledIf("dockerAvailable")`, and auto-skip without Docker.
- Money is `BigDecimal` (domain `Money` value type). Conventional commits (`feat:` / `test:` / `chore:`). NO `Co-Authored-By` lines.

---

## Task 1 — Add jjwt dependencies to `pom.xml`

No test. Pure build-file change; verified by Task 4's first compile.

Edit `backend/pom.xml`. Insert these three dependencies immediately after the `jspecify` dependency block (the `</dependency>` on line 54), before the Flyway block:

```xml
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
```

Verify it resolves:

```
mvn -f backend/pom.xml -B -q dependency:resolve
```

Expect: BUILD SUCCESS, jjwt artifacts downloaded.

**Commit:**

```
git add backend/pom.xml
git commit -m "chore: add jjwt (jwt-api/impl/jackson) for HS256 auth tokens"
```

---

## Task 2 — User read aggregate: domain `Role` enum + `UserModel`

These are framework-free domain types. No standalone test yet (exercised by Task 3's repository test). Create both files.

`backend/src/main/java/com/hireai/domain/biz/user/enums/Role.java`:

```java
package com.hireai.domain.biz.user.enums;

/**
 * Platform roles. Mirrors the {@code users.role} CHECK constraint (CLIENT / BUILDER / ADMIN).
 * Carried in the JWT and surfaced as a {@code ROLE_<name>} Spring authority. Per-endpoint role
 * gating is a later slice; this slice only authenticates.
 */
public enum Role {
    CLIENT,
    BUILDER,
    ADMIN
}
```

`backend/src/main/java/com/hireai/domain/biz/user/model/UserModel.java`:

```java
package com.hireai.domain.biz.user.model;

import com.hireai.domain.biz.user.enums.Role;

import java.util.UUID;

/**
 * Minimal User READ aggregate. Maps the existing {@code users} table (no schema change). Carries
 * just what authentication needs: identity, the BCrypt hash to verify against, the role, and the
 * active flag. Immutable; no behaviour beyond accessors in this slice (login is orchestrated by
 * the app service, not the model).
 */
public record UserModel(UUID id, String email, String passwordHash, Role role, boolean active) {
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/domain/biz/user
git commit -m "feat: add User read aggregate domain model (UserModel + Role)"
```

---

## Task 3 — `UserRepository` port + JPA impl (RED → GREEN)

### 3a. Domain repository port

`backend/src/main/java/com/hireai/domain/biz/user/repository/UserRepository.java`:

```java
package com.hireai.domain.biz.user.repository;

import com.hireai.domain.biz.user.model.UserModel;

import java.util.Optional;

/**
 * Persistence contract for the User read aggregate. One repository per aggregate root; the
 * interface lives in the domain layer with no framework imports. Lookup by email backs login;
 * an empty Optional is the single "no such user" signal (the app service maps both unknown-email
 * and wrong-password to one generic 401, so existence is never leaked).
 */
public interface UserRepository {

    Optional<UserModel> findByEmail(String email);
}
```

### 3b. Failing integration test (RED)

`backend/src/test/java/com/hireai/user/UserRepositoryIntegrationTest.java`:

```java
package com.hireai.user;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies UserRepositoryImpl maps the existing users table: findByEmail hit + miss. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class UserRepositoryIntegrationTest {

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

    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void findsUserByEmail() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'BUILDER', true)",
                id, "repo-test@hireai.local", "$2a$10$abcdefghijklmnopqrstuv");

        Optional<UserModel> found = userRepository.findByEmail("repo-test@hireai.local");

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().role()).isEqualTo(Role.BUILDER);
        assertThat(found.get().passwordHash()).isEqualTo("$2a$10$abcdefghijklmnopqrstuv");
        assertThat(found.get().active()).isTrue();
    }

    @Test
    void returnsEmptyForUnknownEmail() {
        assertThat(userRepository.findByEmail("nobody@hireai.local")).isEmpty();
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=UserRepositoryIntegrationTest
```

Expect: FAIL to compile (`UserRepositoryImpl` / `UserJpaEntity` do not exist yet). If Docker is absent the class is skipped — implement 3c/3d anyway and rely on Task 14's full run on a Docker host.

### 3c. JPA entity

`backend/src/main/java/com/hireai/infrastructure/repository/user/UserJpaEntity.java`:

```java
package com.hireai.infrastructure.repository.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA persistence entity for a user row. Separate from the domain {@code UserModel} so the domain
 * stays framework-free; the repository impl maps between the two. Read-only in this slice (no
 * setters) — users are created by Flyway seed, not by the app.
 */
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

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    protected UserJpaEntity() {
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getRole() { return role; }
    public boolean isActive() { return active; }
}
```

### 3d. Spring Data JPA repository + domain repository impl

`backend/src/main/java/com/hireai/infrastructure/repository/user/UserJpaRepository.java`:

```java
package com.hireai.infrastructure.repository.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for user rows. Internal to infrastructure. */
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByEmail(String email);
}
```

`backend/src/main/java/com/hireai/infrastructure/repository/user/UserRepositoryImpl.java`:

```java
package com.hireai.infrastructure.repository.user;

import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Infrastructure implementation of the domain {@link UserRepository}. Maps a {@code UserJpaEntity}
 * to the framework-free {@code UserModel}, translating the {@code role} TEXT column to the {@link Role}
 * enum.
 */
@Repository
public class UserRepositoryImpl implements UserRepository {

    private final UserJpaRepository userJpa;

    public UserRepositoryImpl(UserJpaRepository userJpa) {
        this.userJpa = userJpa;
    }

    @Override
    public Optional<UserModel> findByEmail(String email) {
        return userJpa.findByEmail(email).map(this::toModel);
    }

    private UserModel toModel(UserJpaEntity e) {
        return new UserModel(e.getId(), e.getEmail(), e.getPasswordHash(),
                Role.valueOf(e.getRole()), e.isActive());
    }
}
```

Run (GREEN, on a Docker host):

```
mvn -f backend/pom.xml -B test -Dtest=UserRepositoryIntegrationTest
```

Expect: PASS (2 tests), or SKIPPED without Docker.

**Commit:**

```
git add backend/src/main/java/com/hireai/domain/biz/user/repository backend/src/main/java/com/hireai/infrastructure/repository/user backend/src/test/java/com/hireai/user
git commit -m "feat: add UserRepository port + JPA impl over existing users table"
```

---

## Task 4 — JWT port: `JwtPrincipal`, `JwtInvalidException`, `JwtService`

Framework-free application port (mirrors `DispatchTokenService`). No test yet (covered by Task 5).

`backend/src/main/java/com/hireai/application/port/security/JwtPrincipal.java`:

```java
package com.hireai.application.port.security;

import java.util.UUID;

/**
 * Verified identity carried by an authentication JWT: the user id (subject) and role. Returned by
 * {@link JwtService#verify(String)} once signature and expiry pass. The filter turns this into the
 * Spring {@code SecurityContext} principal (Hard Invariant #5).
 */
public record JwtPrincipal(UUID userId, String role) {
}
```

`backend/src/main/java/com/hireai/application/port/security/JwtInvalidException.java`:

```java
package com.hireai.application.port.security;

/**
 * Thrown by {@link JwtService#verify(String)} when an auth JWT has a bad signature, is malformed,
 * or is expired. The {@code JwtAuthenticationFilter} catches it and leaves the context
 * unauthenticated (the chain then 401s on protected routes).
 */
public class JwtInvalidException extends RuntimeException {

    public JwtInvalidException(String message) {
        super(message);
    }

    public JwtInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`backend/src/main/java/com/hireai/application/port/security/JwtService.java`:

```java
package com.hireai.application.port.security;

import java.time.Duration;
import java.util.UUID;

/**
 * Application port for issuing and verifying authentication JWTs (Hard Invariant #5). The login app
 * service issues a token bound to a user id + role with a bounded TTL; the {@code JwtAuthenticationFilter}
 * verifies it on every protected request. The HS256-backed implementation lives in
 * {@code infrastructure/security}.
 */
public interface JwtService {

    String issue(UUID userId, String role, Duration ttl);

    JwtPrincipal verify(String token);
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/application/port/security/JwtPrincipal.java backend/src/main/java/com/hireai/application/port/security/JwtInvalidException.java backend/src/main/java/com/hireai/application/port/security/JwtService.java
git commit -m "feat: add JwtService port (JwtPrincipal + JwtInvalidException)"
```

---

## Task 5 — `JjwtService` HS256 impl (RED → GREEN)

### 5a. Failing unit test (RED)

`backend/src/test/java/com/hireai/infrastructure/security/JjwtServiceTest.java`:

```java
package com.hireai.infrastructure.security;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.infrastructure.security.impl.JjwtService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for the HS256 JjwtService: round-trip, tamper rejection, expiry rejection. */
class JjwtServiceTest {

    private static final String SECRET = "test-only-jwt-secret-at-least-32-bytes-long!!";
    private final JjwtService service = new JjwtService(SECRET);

    @Test
    void issuesAndVerifiesRoundTrip() {
        UUID userId = UUID.randomUUID();

        String token = service.issue(userId, "CLIENT", Duration.ofMinutes(5));
        JwtPrincipal principal = service.verify(token);

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo("CLIENT");
    }

    @Test
    void rejectsTamperedToken() {
        String token = service.issue(UUID.randomUUID(), "BUILDER", Duration.ofMinutes(5));
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("a") ? "b" : "a") + token.charAt(token.length() - 1);

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsWrongSecret() {
        String token = service.issue(UUID.randomUUID(), "CLIENT", Duration.ofMinutes(5));
        JjwtService other = new JjwtService("a-different-secret-also-at-least-32-bytes!!");

        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue(UUID.randomUUID(), "CLIENT", Duration.ofSeconds(-1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> service.verify("not-a-jwt"))
                .isInstanceOf(JwtInvalidException.class);
    }

    @Test
    void rejectsTooShortSecretAtConstruction() {
        assertThatThrownBy(() -> new JjwtService("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=JjwtServiceTest
```

Expect: FAIL to compile (`JjwtService` does not exist).

### 5b. Implementation (GREEN)

`backend/src/main/java/com/hireai/infrastructure/security/impl/JjwtService.java`:

```java
package com.hireai.infrastructure.security.impl;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.application.port.security.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * HS256 JWT issue/verify backed by io.jsonwebtoken (jjwt). The subject is the user id; a custom
 * {@code role} claim carries the role. The signing secret is a server-side env value (>= 32 bytes
 * for HS256). {@code verify} throws {@link JwtInvalidException} on a bad signature, malformed token,
 * or expiry. Mirrors {@link HmacDispatchTokenService}'s config-secret + exception style.
 */
@Service
@Slf4j
public class JjwtService implements JwtService {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;

    public JjwtService(@Value("${hireai.auth.jwt-secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("hireai.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String issue(UUID userId, String role, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role)
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
            String role = claims.get(ROLE_CLAIM, String.class);
            return new JwtPrincipal(userId, role);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtInvalidException("Invalid authentication token", ex);
        }
    }
}
```

Run (GREEN):

```
mvn -f backend/pom.xml -B test -Dtest=JjwtServiceTest
```

Expect: PASS (6 tests).

**Commit:**

```
git add backend/src/main/java/com/hireai/infrastructure/security/impl/JjwtService.java backend/src/test/java/com/hireai/infrastructure/security/JjwtServiceTest.java
git commit -m "feat: add HS256 JjwtService (issue/verify) with unit tests"
```

---

## Task 6 — Auth config properties + `BCryptPasswordEncoder` bean

### 6a. application.yml properties

Edit `backend/src/main/resources/application.yml`. Add an `auth:` block under the existing `hireai:` section (after the `dispatch:` block, keeping its indentation):

```yaml
  auth:
    # HS256 signing secret for authentication JWTs. MUST be overridden in real deploys.
    # Dev default is >= 32 bytes (HS256 minimum). Never a real secret in git.
    jwt-secret: ${AUTH_JWT_SECRET:dev-only-auth-jwt-secret-change-me-32bytes!!}
    # Access-token lifetime in seconds (default 24h). Login only; no refresh in this slice.
    jwt-ttl-seconds: ${AUTH_JWT_TTL_SECONDS:86400}
```

### 6b. PasswordEncoder bean

Create `backend/src/main/java/com/hireai/controller/config/PasswordEncoderConfig.java` (kept beside `SecurityConfig`, the existing security-config home):

```java
package com.hireai.controller.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Single application-wide password encoder. BCrypt verifies the seeded demo password hashes
 * (Flyway V5) during login. Profile-independent so both the secured default chain and the
 * permissive test chain share one encoder bean.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/resources/application.yml backend/src/main/java/com/hireai/controller/config/PasswordEncoderConfig.java
git commit -m "feat: add auth jwt config properties + BCryptPasswordEncoder bean"
```

---

## Task 7 — Auth application service: carriers + port + impl (RED → GREEN)

### 7a. Carriers + auth failure exception

`backend/src/main/java/com/hireai/application/biz/auth/LoginInfo.java`:

```java
package com.hireai.application.biz.auth;

/** Inbound carrier for a login attempt. Built by the controller from the validated request DTO. */
public record LoginInfo(String email, String password) {
}
```

`backend/src/main/java/com/hireai/application/biz/auth/AuthResult.java`:

```java
package com.hireai.application.biz.auth;

import java.util.UUID;

/** Result of a successful login: the signed JWT plus the resolved identity for the response body. */
public record AuthResult(String token, UUID userId, String role) {
}
```

`backend/src/main/java/com/hireai/application/biz/auth/AuthenticationFailedException.java`:

```java
package com.hireai.application.biz.auth;

/**
 * Raised on ANY login failure — unknown email, wrong password, or inactive account. Deliberately
 * one exception with one generic message so the API never reveals which users exist (no
 * enumeration). The global exception handler maps it to HTTP 401.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password");
    }
}
```

### 7b. App service port

`backend/src/main/java/com/hireai/application/biz/auth/AuthAppService.java`:

```java
package com.hireai.application.biz.auth;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

/**
 * Orchestrates the login use case: look up the user by email, verify the BCrypt password, and issue
 * a JWT. Every failure path (unknown email / wrong password / inactive) throws a single
 * {@link AuthenticationFailedException} so the API leaks no user-existence information.
 */
@Validated
public interface AuthAppService {

    AuthResult login(@NonNull LoginInfo loginInfo);
}
```

### 7c. Failing unit test (RED)

`backend/src/test/java/com/hireai/application/biz/auth/impl/AuthAppServiceImplTest.java`:

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.AuthenticationFailedException;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.enums.Role;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for login orchestration: success issues a token; every failure path throws the generic 401. */
class AuthAppServiceImplTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AuthAppServiceImpl service =
            new AuthAppServiceImpl(userRepository, jwtService, encoder, 86400L);

    @Test
    void issuesTokenOnValidCredentials() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, Role.CLIENT, true)));
        when(jwtService.issue(eq(userId), eq("CLIENT"), any(Duration.class))).thenReturn("signed.jwt.token");

        AuthResult result = service.login(new LoginInfo("a@hireai.local", "correct-horse"));

        assertThat(result.token()).isEqualTo("signed.jwt.token");
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.role()).isEqualTo("CLIENT");
    }

    @Test
    void throwsOnWrongPassword() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, Role.CLIENT, true)));

        assertThatThrownBy(() -> service.login(new LoginInfo("a@hireai.local", "wrong")))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void throwsOnUnknownEmail() {
        when(userRepository.findByEmail("ghost@hireai.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginInfo("ghost@hireai.local", "whatever")))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void throwsOnInactiveUser() {
        UUID userId = UUID.randomUUID();
        String hash = encoder.encode("correct-horse");
        when(userRepository.findByEmail("a@hireai.local"))
                .thenReturn(Optional.of(new UserModel(userId, "a@hireai.local", hash, Role.CLIENT, false)));

        assertThatThrownBy(() -> service.login(new LoginInfo("a@hireai.local", "correct-horse")))
                .isInstanceOf(AuthenticationFailedException.class);
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=AuthAppServiceImplTest
```

Expect: FAIL to compile (`AuthAppServiceImpl` does not exist).

### 7d. Implementation (GREEN)

`backend/src/main/java/com/hireai/application/biz/auth/impl/AuthAppServiceImpl.java`:

```java
package com.hireai.application.biz.auth.impl;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.AuthenticationFailedException;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.application.port.security.JwtService;
import com.hireai.domain.biz.user.model.UserModel;
import com.hireai.domain.biz.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Login orchestration. Looks up the user by email, verifies the BCrypt password against the stored
 * hash, checks the account is active, then issues a JWT bound to the user id + role. Every failure
 * mode collapses to {@link AuthenticationFailedException} (generic 401) — no user enumeration. The
 * password check always runs against a real BCrypt verify; an unknown email returns early but the
 * timing difference is acceptable for this FYP slice (account-lockout / constant-time is out of scope).
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class AuthAppServiceImpl implements AuthAppService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final long jwtTtlSeconds;

    public AuthAppServiceImpl(UserRepository userRepository,
                              JwtService jwtService,
                              PasswordEncoder passwordEncoder,
                              @Value("${hireai.auth.jwt-ttl-seconds}") long jwtTtlSeconds) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
        this.jwtTtlSeconds = jwtTtlSeconds;
    }

    @Override
    public AuthResult login(LoginInfo loginInfo) {
        UserModel user = userRepository.findByEmail(loginInfo.email())
                .orElseThrow(AuthenticationFailedException::new);
        if (!user.active()) {
            throw new AuthenticationFailedException();
        }
        if (user.passwordHash() == null
                || !passwordEncoder.matches(loginInfo.password(), user.passwordHash())) {
            throw new AuthenticationFailedException();
        }
        String role = user.role().name();
        String token = jwtService.issue(user.id(), role, Duration.ofSeconds(jwtTtlSeconds));
        log.info("User {} logged in (role {})", user.id(), role);
        return new AuthResult(token, user.id(), role);
    }
}
```

Run (GREEN):

```
mvn -f backend/pom.xml -B test -Dtest=AuthAppServiceImplTest
```

Expect: PASS (4 tests).

**Commit:**

```
git add backend/src/main/java/com/hireai/application/biz/auth backend/src/test/java/com/hireai/application/biz/auth
git commit -m "feat: add AuthAppService login (BCrypt verify -> JWT, generic 401)"
```

---

## Task 8 — Map `AuthenticationFailedException` to HTTP 401 in the global advice

No new test (asserted by Task 14's `AuthIntegrationTest.loginWithWrongPasswordIs401`). Edit `backend/src/main/java/com/hireai/controller/config/GlobalExceptionConfiguration.java`.

Add the import:

```java
import com.hireai.application.biz.auth.AuthenticationFailedException;
```

Add this handler method inside the class, immediately before `handleUnexpected`:

```java
    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<WebResult<Void>> handleAuthFailure(AuthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(WebResult.error(ResultCode.VALIDATION_ERROR, ex.getMessage()));
    }
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/config/GlobalExceptionConfiguration.java
git commit -m "feat: map AuthenticationFailedException to HTTP 401"
```

---

## Task 9 — AuthController + DTOs

No new test here (the controller is exercised by the `AuthIntegrationTest` in Task 12 and by the `AuthAppServiceImplTest` already covering the service). Create the DTOs and controller.

`backend/src/main/java/com/hireai/controller/biz/auth/dto/LoginRequest.java`:

```java
package com.hireai.controller.biz.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Inbound HTTP DTO for login. Bean Validation at the boundary; credentials are checked downstream. */
public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 200) String password
) {
}
```

`backend/src/main/java/com/hireai/controller/biz/auth/dto/LoginResponse.java`:

```java
package com.hireai.controller.biz.auth.dto;

import java.util.UUID;

/** Outbound HTTP DTO for a successful login: the bearer token plus the resolved identity. */
public record LoginResponse(String token, UUID userId, String role) {
}
```

`backend/src/main/java/com/hireai/controller/biz/auth/AuthController.java`:

```java
package com.hireai.controller.biz.auth;

import com.hireai.application.biz.auth.AuthAppService;
import com.hireai.application.biz.auth.AuthResult;
import com.hireai.application.biz.auth.LoginInfo;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.auth.dto.LoginRequest;
import com.hireai.controller.biz.auth.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication HTTP surface. Thin: validate the request, delegate to the app service, wrap the
 * result. {@code POST /api/auth/login} is the only endpoint and is permitAll in the security chain
 * (you cannot have a token before you log in). Bad credentials surface as HTTP 401 via the global
 * exception handler (generic message — no user enumeration).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController extends BaseController {

    private final AuthAppService authAppService;

    public AuthController(AuthAppService authAppService) {
        this.authAppService = authAppService;
    }

    @PostMapping("/login")
    public WebResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authAppService.login(new LoginInfo(request.email(), request.password()));
        return ok(new LoginResponse(result.token(), result.userId(), result.role()));
    }
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/biz/auth
git commit -m "feat: add AuthController POST /api/auth/login + DTOs"
```

---

## Task 10 — `JwtAuthenticationFilter` + `JwtCurrentUserProvider` (RED → GREEN)

### 10a. Failing unit test for the filter (RED)

`backend/src/test/java/com/hireai/controller/config/JwtAuthenticationFilterTest.java`:

```java
package com.hireai.controller.config;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.application.port.security.JwtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the bearer-token filter: valid token sets the principal; missing/invalid leaves it empty. */
class JwtAuthenticationFilterTest {

    private final JwtService jwtService = mock(JwtService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setsAuthenticationForValidBearerToken() throws Exception {
        UUID userId = UUID.randomUUID();
        when(jwtService.verify("good")).thenReturn(new JwtPrincipal(userId, "CLIENT"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer good");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isEqualTo(userId);
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_CLIENT");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void leavesContextEmptyWhenHeaderMissing() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void leavesContextEmptyWhenTokenInvalid() throws Exception {
        when(jwtService.verify("bad")).thenThrow(new JwtInvalidException("nope"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=JwtAuthenticationFilterTest
```

Expect: FAIL to compile (`JwtAuthenticationFilter` does not exist).

### 10b. The filter (GREEN)

`backend/src/main/java/com/hireai/controller/config/JwtAuthenticationFilter.java`:

```java
package com.hireai.controller.config;

import com.hireai.application.port.security.JwtInvalidException;
import com.hireai.application.port.security.JwtPrincipal;
import com.hireai.application.port.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads {@code Authorization: Bearer <jwt>}, verifies it via {@link JwtService}, and on success sets
 * a {@link UsernamePasswordAuthenticationToken} whose principal is the user id (UUID) and whose single
 * authority is {@code ROLE_<role>}. A missing/blank header or an invalid token leaves the context
 * unauthenticated — the security chain then returns 401 on protected routes (Hard Invariant #5).
 * Never writes a response itself; it only populates the context.
 */
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                JwtPrincipal principal = jwtService.verify(token);
                var authority = new SimpleGrantedAuthority("ROLE_" + principal.role());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.userId(), null, List.of(authority));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtInvalidException ex) {
                log.debug("Rejected invalid auth token: {}", ex.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
```

### 10c. JwtCurrentUserProvider (GREEN)

`backend/src/main/java/com/hireai/controller/config/JwtCurrentUserProvider.java`:

```java
package com.hireai.controller.config;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production {@link CurrentUserProvider}: returns the authenticated user id placed in the
 * {@code SecurityContext} by {@link JwtAuthenticationFilter} (Hard Invariant #5 — identity comes only
 * from the verified JWT). Throws {@link IllegalStateException} if called without an authenticated
 * principal (a programming error: protected routes are gated by the security chain, so a controller
 * never runs unauthenticated). Active outside the {@code test} profile; the {@code test} profile uses
 * {@link DevCurrentUserProvider}.
 */
@Component
@Profile("!test")
public class JwtCurrentUserProvider implements CurrentUserProvider {

    @Override
    public UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UUID userId)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return userId;
    }
}
```

### 10d. Flip DevCurrentUserProvider to the test profile

Edit `backend/src/main/java/com/hireai/controller/config/DevCurrentUserProvider.java`. Change the annotation only:

```java
@Profile("test")
```

(was `@Profile("!prod")`). Update its class Javadoc final sentence to: `This bean is active only under the "test" profile; production uses JwtCurrentUserProvider.`

Run (GREEN — compiles the providers and runs the filter test):

```
mvn -f backend/pom.xml -B test -Dtest=JwtAuthenticationFilterTest
```

Expect: PASS (3 tests).

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/config/JwtAuthenticationFilter.java backend/src/main/java/com/hireai/controller/config/JwtCurrentUserProvider.java backend/src/main/java/com/hireai/controller/config/DevCurrentUserProvider.java backend/src/test/java/com/hireai/controller/config/JwtAuthenticationFilterTest.java
git commit -m "feat: add JwtAuthenticationFilter + JwtCurrentUserProvider (test-profile bypass)"
```

---

## Task 11 — `SecurityConfig`: two profile-scoped filter chains

No new unit test (covered by the existing `AgentCallbackControllerTest` under `test`, and by Task 12's `AuthIntegrationTest` under the default profile). Fully replace `backend/src/main/java/com/hireai/controller/config/SecurityConfig.java`:

```java
package com.hireai.controller.config;

import com.hireai.application.port.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Two profile-scoped security chains; exactly one is active per profile.
 *
 * <p><b>Default / prod / dev</b> ({@code @Profile("!test")}): stateless, CSRF off, JWT-authenticated by
 * default. Only login, the agent result callback (dispatch-token authenticated — Hard Invariant #6),
 * and the health probe are public; everything else requires a valid JWT. The {@link JwtAuthenticationFilter}
 * runs before the username/password filter and populates the {@code SecurityContext}; an unauthenticated
 * request to a protected route gets a 401 (no redirect).</p>
 *
 * <p><b>{@code test}</b>: a permissive chain (permitAll, CSRF off) so the existing integration / controller
 * tests run with {@link DevCurrentUserProvider} and do not need to mint tokens. Activated by
 * {@code @ActiveProfiles("test")}.</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    @Profile("!test")
    public SecurityFilterChain securedFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/agent-callbacks/**").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("test")
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/config/SecurityConfig.java
git commit -m "feat: enforce JWT auth by default with a permissive test-profile chain"
```

---

## Task 12 — Flyway `V5__seed_demo_users.sql` (generate the BCrypt hash, then assert it)

The demo password is `DemoPass123!`. BCrypt hashes are salted, so the executor MUST generate a real hash and paste it — do NOT invent one.

### 12a. Generate the BCrypt hash at build time

Run this throwaway jshell snippet (uses the BCrypt encoder already on the test classpath). On a Docker-less machine this still works — it needs no DB:

```
mvn -f backend/pom.xml -B -q dependency:build-classpath -Dmdep.outputFile=cp.txt
jshell --class-path "$(cat backend/cp.txt)" -q -s -<<'EOF'
System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("DemoPass123!"));
EOF
```

If `jshell` is unavailable, instead add a temporary printing test and run it:

```java
// backend/src/test/java/com/hireai/auth/GenerateDemoHash.java  (DELETE after copying the output)
package com.hireai.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class GenerateDemoHash {
    @Test
    void printHash() {
        System.out.println("DEMO_HASH=" + new BCryptPasswordEncoder().encode("DemoPass123!"));
    }
}
```

```
mvn -f backend/pom.xml -B test -Dtest=GenerateDemoHash
```

Copy the printed `$2a$10$...` value. Then DELETE `GenerateDemoHash.java` and `backend/cp.txt`.

> In the SQL and the verification test below, the literal `REPLACE_WITH_GENERATED_BCRYPT_HASH` MUST be replaced with the exact generated string. Use the SAME hash in both files.

### 12b. The migration

`backend/src/main/resources/db/migration/V5__seed_demo_users.sql`:

```sql
-- V5: Seed two demo users (CLIENT + BUILDER) with BCrypt password hashes and wallets, so the
-- secured demo can log in (default profile enforces JWT; there is no register endpoint in this slice).
-- Fixed UUIDs make this run-once and idempotent under Flyway's single-apply guarantee.
-- Demo password (documented, throwaway accounts only): DemoPass123!
-- The hash below is a BCrypt hash of that password, generated at build time (see plan Task 12a).

INSERT INTO users (id, email, password_hash, role, is_active) VALUES
    ('00000000-0000-0000-0000-000000000010', 'client@hireai.local',
     'REPLACE_WITH_GENERATED_BCRYPT_HASH', 'CLIENT', true),
    ('00000000-0000-0000-0000-000000000011', 'builder@hireai.local',
     'REPLACE_WITH_GENERATED_BCRYPT_HASH', 'BUILDER', true);

-- Wallets: the client wallet is funded so the escrow demo (task submit -> freeze) works end-to-end.
INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES
    ('00000000-0000-0000-0000-000000000020', '00000000-0000-0000-0000-000000000010', 1000.00, 0.00),
    ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000011', 0.00, 0.00);
```

### 12c. Unit test asserting the seeded hash matches the demo password

`backend/src/test/java/com/hireai/auth/SeedDemoPasswordHashTest.java`:

```java
package com.hireai.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the V5 seed: the BCrypt hash pasted into V5__seed_demo_users.sql MUST verify against the
 * documented demo password. If someone regenerates the migration or mistypes the hash, this fails
 * fast (pure unit test — no DB). Keep this hash byte-for-byte identical to the one in V5.
 */
class SeedDemoPasswordHashTest {

    /** Paste the EXACT same hash that is in V5__seed_demo_users.sql. */
    private static final String SEEDED_HASH = "REPLACE_WITH_GENERATED_BCRYPT_HASH";

    @Test
    void seededHashMatchesDemoPassword() {
        assertThat(new BCryptPasswordEncoder().matches("DemoPass123!", SEEDED_HASH)).isTrue();
    }
}
```

Run:

```
mvn -f backend/pom.xml -B test -Dtest=SeedDemoPasswordHashTest
```

Expect: PASS (1 test). If it FAILS, the hash in V5 and/or this test is wrong — regenerate via 12a and paste the identical value into both files.

**Commit:**

```
git add backend/src/main/resources/db/migration/V5__seed_demo_users.sql backend/src/test/java/com/hireai/auth/SeedDemoPasswordHashTest.java
git commit -m "feat: seed demo CLIENT/BUILDER users + wallets (Flyway V5) with hash guard"
```

---

## Task 13 — Regression: add `@ActiveProfiles("test")` to existing context-loading tests

Every test that boots a Spring context now instantiates the controllers (which need a `CurrentUserProvider`) and the two profile-scoped chains. Under no profile, both `JwtCurrentUserProvider` and `DevCurrentUserProvider` would be ambiguous-free (one is `!test`, one is `test`) — but a `@SpringBootTest` with NO profile activates the `!test` JWT chain + `JwtCurrentUserProvider`, which is fine for tests that call app services directly yet WRONG for any that go through HTTP expecting permitAll. To keep the suite stable and the bypass explicit, annotate each context-loading test with `@ActiveProfiles("test")`.

Pure-unit tests (no Spring context) are NOT touched.

Apply the change to EACH of these seven classes. For the six `@SpringBootTest` classes, add the import `import org.springframework.test.context.ActiveProfiles;` (if absent) and the annotation `@ActiveProfiles("test")` directly above the class declaration (below the other type annotations).

1. `backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java` — add `@ActiveProfiles("test")`.
2. `backend/src/test/java/com/hireai/wallet/WalletLedgerIntegrationTest.java` — add `@ActiveProfiles("test")`.
3. `backend/src/test/java/com/hireai/dispatch/DispatchRoundTripIntegrationTest.java` — add `@ActiveProfiles("test")`.
4. `backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java` — add `@ActiveProfiles("test")`.
5. `backend/src/test/java/com/hireai/task/AgentCallbackIntegrationTest.java` — add `@ActiveProfiles("test")`.
6. `backend/src/test/java/com/hireai/routing/RoutingIntegrationTest.java` — it already has `@ActiveProfiles("dev")`. **Change it to `@ActiveProfiles({"dev", "test"})`** so the permissive chain + `DevCurrentUserProvider` activate (its real-HTTP call to `/api/agent-callbacks/...` is permitAll in both chains, but `test` keeps it consistent and avoids needing a JWT). The `dev` profile is retained because the class Javadoc references it; no `application-dev.yml` exists, so `dev` is otherwise a no-op.
7. `backend/src/test/java/com/hireai/controller/biz/agentcallback/AgentCallbackControllerTest.java` — this `@WebMvcTest(AgentCallbackController.class) @Import(SecurityConfig.class)` currently relies on a single permitAll chain. Add `@ActiveProfiles("test")` so the `@Profile("test") permissiveFilterChain` bean is the one selected from the imported `SecurityConfig` (otherwise the `!test securedFilterChain` would load and its `JwtService` dependency is absent in the slice context). Add the import and annotation. Keep the existing `@WithMockUser`.

> Why #7 matters: with two `@Profile`-scoped beans in `SecurityConfig`, a `@WebMvcTest` that imports `SecurityConfig` with NO active profile would try to build `securedFilterChain`, which needs a `JwtService` bean not present in the web slice — startup fails. `@ActiveProfiles("test")` selects the dependency-free `permissiveFilterChain`. The three callback assertions (200 on valid token, 401 on invalid, 401 on missing header) are produced by the controller-local `@ExceptionHandler`, not the chain, so they still hold under permitAll.

After editing all seven, run the affected classes' non-Docker counterpart to confirm compilation, then the full suite (Task 14). Quick compile check:

```
mvn -f backend/pom.xml -B -q -DskipTests test-compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/test/java/com/hireai/agent/AgentRegistrationIntegrationTest.java backend/src/test/java/com/hireai/wallet/WalletLedgerIntegrationTest.java backend/src/test/java/com/hireai/dispatch/DispatchRoundTripIntegrationTest.java backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java backend/src/test/java/com/hireai/task/AgentCallbackIntegrationTest.java backend/src/test/java/com/hireai/routing/RoutingIntegrationTest.java backend/src/test/java/com/hireai/controller/biz/agentcallback/AgentCallbackControllerTest.java
git commit -m "test: pin existing context-loading tests to the permissive test profile"
```

---

## Task 14 — `AuthIntegrationTest` (default profile, real Postgres) + full-suite green

This is the capstone integration test. It runs under the **default profile** (NO `@ActiveProfiles`) so the secured chain + `JwtCurrentUserProvider` are exercised for real: seed-user login returns a token, a protected endpoint needs it, and the callback works token-only without a JWT.

### 14a. The integration test

`backend/src/test/java/com/hireai/auth/AuthIntegrationTest.java`:

```java
package com.hireai.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.routing.RoutingAppService;
import com.hireai.application.port.security.DispatchTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * End-to-end auth under the DEFAULT (secured) profile: Flyway V5 seeds client@hireai.local. Logs in,
 * gets a JWT, then proves (a) GET /api/agents with the token -> 200, (b) without it -> 401, and
 * (c) the agent callback is reachable token-only (no JWT) but still rejects a bad dispatch token.
 * Routing is mocked so task submission side effects don't interfere; the DispatchTokenService is
 * mocked so the callback's 401-on-bad-token path is deterministic without a real signed token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class AuthIntegrationTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper objectMapper;

    @MockBean RoutingAppService routingAppService;
    @MockBean DispatchTokenService dispatchTokenService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String loginAndGetToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"client@hireai.local\",\"password\":\"DemoPass123!\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/auth/login"), new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        return json.path("data").path("token").asText();
    }

    @Test
    void seedUserLogsInAndAccessesProtectedEndpoint() throws Exception {
        String token = loginAndGetToken();
        assertThat(token).isNotBlank();

        HttpHeaders authed = new HttpHeaders();
        authed.setBearerAuth(token);
        ResponseEntity<String> ok = rest.exchange(
                url("/api/agents"), HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedEndpointRejectsAnonymous() {
        ResponseEntity<String> resp = rest.getForEntity(url("/api/agents"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithWrongPasswordIs401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"email\":\"client@hireai.local\",\"password\":\"wrong\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/auth/login"), new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void agentCallbackIsReachableWithoutJwtButRejectsBadDispatchToken() {
        // The callback path is permitAll in the secured chain (no JWT needed). Reaching the
        // controller (past Spring Security) it is gated by the dispatch token instead: the real
        // AgentCallbackAppService calls the @MockBean DispatchTokenService, whose verify(...) is
        // stubbed below to throw, so the controller-local handler returns 401 — proving the
        // callback is authenticated by the dispatch token, not the JWT (invariant #6).
        when(dispatchTokenService.verify(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.hireai.application.port.security.DispatchTokenInvalidException("bad"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("not-a-real-dispatch-token");
        String body = "{\"agentStatus\":\"COMPLETED\",\"resultPayloadJson\":\"{}\","
                + "\"resultUrl\":\"https://x/y\",\"message\":\"done\"}";
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/agent-callbacks/" + java.util.UUID.randomUUID() + "/result"),
                new HttpEntity<>(body, headers), String.class);
        // Reachable (not 401-from-security / not 403): the dispatch-token path handles auth.
        // With a mocked DispatchTokenService.verify throwing DispatchTokenInvalidException, the
        // controller-local handler returns 401 — proving the callback is gated by the token, not JWT.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

> Note on what this proves: a request to the callback path is NOT blocked by the JWT security chain (it is permitAll), so it reaches the controller; the stubbed `DispatchTokenService.verify(...)` then throws and the controller-local `@ExceptionHandler` returns 401 with the `WebResult` envelope (`"code":"VALIDATION_ERROR"`). That envelope can only be produced past the security layer — confirming the callback is authenticated by the dispatch token, not the JWT (invariant #6).

Run (on a Docker host):

```
mvn -f backend/pom.xml -B test -Dtest=AuthIntegrationTest
```

Expect: PASS (4 tests), or SKIPPED without Docker.

### 14b. Full suite green

```
mvn -f backend/pom.xml -B test
```

Expect: BUILD SUCCESS. All previously-green tests stay green; new auth tests pass; integration tests run on a Docker host or skip cleanly without Docker. If any existing `@SpringBootTest` fails to start with a `CurrentUserProvider`/`SecurityFilterChain` bean ambiguity, re-check it carries `@ActiveProfiles("test")` (Task 13).

**Commit:**

```
git add backend/src/test/java/com/hireai/auth/AuthIntegrationTest.java
git commit -m "test: add AuthIntegrationTest (login -> token -> protected 200/401, callback token-only)"
```

---

## Done criteria

- `POST /api/auth/login` returns a JWT for a seeded user and 401 (generic) for bad credentials.
- Protected endpoints (e.g. `GET /api/agents`) return 401 without a valid token and 200 with one, under the default profile.
- The agent callback remains reachable without a JWT and is still gated by its dispatch token (invariant #6 untouched).
- `mvn -f backend/pom.xml -B test` is green; the 7 enumerated context-loading tests run under the permissive `test` profile; new unit + integration auth tests pass (integration auto-skips without Docker).
- No `Co-Authored-By` lines; all commits use conventional prefixes.
