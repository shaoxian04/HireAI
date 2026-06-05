# Task Result Endpoint Implementation Plan (`GET /api/tasks/{taskId}/result`)

**REQUIRED SUB-SKILL:** `agentic-workers:executing-plans` — execute this plan one task at a time, run the named test command after every step, and STOP at each checkpoint until the command output matches the stated expectation.

## Goal

Add the single backend read endpoint the frontend demo slice needs to display an Agent's actual output: **`GET /api/tasks/{taskId}/result` → `WebResult<TaskResultDTO>`**. It loads the task, **owner-checks the caller exactly like `TaskController.getById`** (identity from the JWT principal via `CurrentUserProvider`, never the path — Hard Invariant #5), reads the `TaskResultModel` child **through the Task aggregate root**, and returns a not-found `ResultCode` (→ HTTP 404) when the task has no result yet (it is not `RESULT_RECEIVED`). The UI treats that 404 as a "pending, keep polling" signal.

This is Part A of `docs/superpowers/specs/2026-06-06-frontend-demo-slice-design.md`. It is a pure read addition: **no schema change** (reads the existing `task_results` table from Flyway `V4`), no new domain behaviour, no new aggregate.

## Architecture

DDD layering is already in place: `controller → application → domain ← infrastructure`; the domain is framework-free. This slice touches only the controller and application read layers and reuses everything below:

- **`TaskModel.result()`** already exposes the `TaskResultModel` child, and `TaskRepositoryImpl.findById` already loads that child through the root (`taskResultJpa.findByTaskId`). **No repository change.**
- **`TaskReadAppService`** already owns the owner-check pattern in `getForClient` (load → if `clientId` mismatch throw `NOT_FOUND`, so existence is never leaked across clients). We add a sibling read method `getResult` that reuses that exact ownership logic and adds the "no result yet" → `NOT_FOUND` case.
- **`TaskController`** already derives identity from `CurrentUserProvider.currentUserId()` and wraps every payload in `WebResult`. We add one `@GetMapping("/{taskId}/result")` method.
- **`GlobalExceptionConfiguration`** already maps `DomainException(NOT_FOUND)` → HTTP 404. No handler change.

New artifacts (all small):

| File | Layer | Purpose |
|---|---|---|
| `controller/biz/task/dto/TaskResultDTO.java` | controller | Outbound DTO (no domain types leak) |
| `controller/biz/task/converter/TaskResult2DTOConverter.java` | controller | Hand-written `TaskResultModel` → `TaskResultDTO` |
| `application/biz/task/TaskReadAppService#getResult` (+ impl) | application | Load + owner-check + read child + not-found-when-absent |
| `controller/biz/task/TaskController#getResult` | controller | `GET /{taskId}/result` |
| `controller/biz/task/TaskControllerTest.java` | test | `@WebMvcTest` (`@ActiveProfiles("test")`) |
| `task/TaskResultEndpointIntegrationTest.java` | test | Testcontainers, owner-scoped read paths |

### Owner-check + not-found contract (decisions locked)

`getResult(taskId, clientId)` resolves exactly three outcomes, all via the existing `DomainException` → HTTP mapping in `GlobalExceptionConfiguration`:

| Situation | Thrown | ResultCode | HTTP |
|---|---|---|---|
| Task exists, owned by caller, has a result | — (returns DTO) | `SUCCESS` | 200 |
| Task does not exist | `DomainException` | `NOT_FOUND` | 404 |
| Task exists but `clientId` ≠ caller | `DomainException` | `NOT_FOUND` | 404 (existence not leaked — same as `getForClient`) |
| Task owned by caller but `result()` is `null` (not `RESULT_RECEIVED`) | `DomainException` | `NOT_FOUND` | 404 (UI reads as "pending") |

The not-owner path returns the **same** `NOT_FOUND` the spec ("404/403 per the current owner-check pattern — mirror `TaskController.getById`") permits; we mirror `getForClient`, which uses 404, so existence is never leaked.

## Tech Stack

- Java 21, Spring Boot 3.3.5, Spring Web + Security, Spring Data JPA, Flyway, PostgreSQL.
- Tests: JUnit 5, AssertJ, Mockito, `spring-security-test` (present), Testcontainers Postgres (present). `@WebMvcTest` controller test + Testcontainers integration test, both `@ActiveProfiles("test")`. The integration test is named `*IntegrationTest`, gated by `@EnabledIf("dockerAvailable")`, and **auto-skips when no Docker daemon is reachable** (never fails the build).
- App-service interface params carry JSpecify `@NonNull` (matching the existing `TaskReadAppService`).
- Conventional commits (`feat:` / `test:`). **NO `Co-Authored-By` lines.**
- All test commands run from the repo root via `mvn -f backend/pom.xml ...`.

### Why the integration test seeds via JDBC

The existing `AgentCallbackIntegrationTest` drives the full lifecycle (submit → assignAndQueue → markExecuting → token-verified callback) to produce a result, which forces mocking `RoutingAppService` and `DispatchTokenService`. This endpoint is a **pure read**, so this plan seeds the `tasks` and `task_results` rows **directly with `JdbcTemplate`** (the test already autowires it). These two tables carry **no append-only triggers** (only `ledger_entries` / `reputation_events` do), so a direct `INSERT` is legitimate and keeps the test focused on the read path with zero extra mocks.

---

## Task 1 — `TaskResultDTO` outbound DTO

Pure new file; no standalone test (exercised by Task 3's converter test and the endpoint tests). Mirrors `TaskDTO`'s record style (no domain types cross the boundary).

Create `backend/src/main/java/com/hireai/controller/biz/task/dto/TaskResultDTO.java`:

```java
package com.hireai.controller.biz.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound HTTP DTO for the single result an Agent posted back for a task. No domain types leak
 * across the boundary. {@code resultPayloadJson} is the raw JSONB payload as a string (the UI
 * pretty-prints it); {@code resultUrl} is nullable.
 */
public record TaskResultDTO(
        UUID taskId,
        String agentStatus,
        String resultPayloadJson,
        String resultUrl,
        Instant receivedAt
) {
}
```

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/biz/task/dto/TaskResultDTO.java
git commit -m "feat: add TaskResultDTO outbound DTO for the task result endpoint"
```

---

## Task 2 — `TaskResult2DTOConverter` (RED → GREEN)

### 2a. Failing unit test (RED)

The converter is a plain static function, unit-testable without Spring. Create `backend/src/test/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverterTest.java`:

```java
package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the hand-written TaskResultModel -> TaskResultDTO mapping (incl. nullable resultUrl). */
class TaskResult2DTOConverterTest {

    @Test
    void mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-06-06T10:15:30Z");
        TaskResultModel model = TaskResultModel.rehydrate(
                id, taskId, "COMPLETED", "{\"summary\":\"ok\"}", "https://x/y", receivedAt);

        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(model);

        assertThat(dto.taskId()).isEqualTo(taskId);
        assertThat(dto.agentStatus()).isEqualTo("COMPLETED");
        assertThat(dto.resultPayloadJson()).isEqualTo("{\"summary\":\"ok\"}");
        assertThat(dto.resultUrl()).isEqualTo("https://x/y");
        assertThat(dto.receivedAt()).isEqualTo(receivedAt);
    }

    @Test
    void mapsNullResultUrl() {
        TaskResultModel model = TaskResultModel.rehydrate(
                UUID.randomUUID(), UUID.randomUUID(), "COMPLETED", "{}", null,
                Instant.parse("2026-06-06T10:15:30Z"));

        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(model);

        assertThat(dto.resultUrl()).isNull();
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=TaskResult2DTOConverterTest
```

Expect: FAIL to compile (`TaskResult2DTOConverter` does not exist).

### 2b. Implementation (GREEN)

Create `backend/src/main/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverter.java`:

```java
package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskResultDTO;
import com.hireai.domain.biz.task.model.TaskResultModel;

/**
 * Explicit, hand-written converter from the {@link TaskResultModel} child entity to its outbound
 * DTO. One direction only; no auto-mapping, so what crosses the boundary is deliberate. Mirrors
 * {@code TaskModel2DTOConverter}.
 */
public final class TaskResult2DTOConverter {

    private TaskResult2DTOConverter() {
    }

    public static TaskResultDTO toDTO(TaskResultModel result) {
        return new TaskResultDTO(
                result.taskId(),
                result.agentStatus(),
                result.resultPayloadJson(),
                result.resultUrl(),
                result.receivedAt());
    }
}
```

Run (GREEN):

```
mvn -f backend/pom.xml -B test -Dtest=TaskResult2DTOConverterTest
```

Expect: PASS (2 tests).

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverter.java backend/src/test/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverterTest.java
git commit -m "feat: add TaskResult2DTOConverter (TaskResultModel -> TaskResultDTO)"
```

---

## Task 3 — `TaskReadAppService.getResult` port method

Add the read method to the existing interface. JSpecify `@NonNull` on both params (matching `getForClient`). No standalone test (covered by the integration test in Task 5). Edit `backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java`.

Add the import (the others are already present):

```java
import com.hireai.domain.biz.task.model.TaskResultModel;
```

Add this method to the interface, immediately after `getForClient`:

```java
    TaskResultModel getResult(@NonNull UUID taskId, @NonNull UUID clientId);
```

Update the interface Javadoc to mention the new read. Replace the existing class Javadoc block with:

```java
/**
 * Orchestrates task READ use cases. Enforces Hard Invariant #5 (server-side identity +
 * ownership): a task is only returned to the client that owns it; otherwise NOT_FOUND,
 * so existence is not leaked across clients. {@link #getResult} additionally returns NOT_FOUND
 * when an owned task has no result yet (it is not RESULT_RECEIVED), which the UI reads as
 * "pending, keep polling". {@link #getRoutingView} is an internal, non-owner-scoped read used by
 * the routing module (no client identity is involved in routing).
 */
```

Verify it compiles (the impl is added next; this confirms the interface alone is valid):

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: FAIL to compile — `TaskReadAppServiceImpl` no longer implements the interface (`getResult` is abstract and unimplemented). This is the expected RED for Task 4; do **not** commit yet. (If you prefer a clean compile gate here, implement Task 4 before running compile.)

---

## Task 4 — `TaskReadAppServiceImpl.getResult` implementation (GREEN)

Implement the method, reusing the existing owner-check shape and adding the not-found-when-absent case. Edit `backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java`.

Add the import:

```java
import com.hireai.domain.biz.task.model.TaskResultModel;
```

Add this method immediately after `getForClient`:

```java
    @Override
    public TaskResultModel getResult(UUID taskId, UUID clientId) {
        TaskModel task = getForClient(taskId, clientId);
        TaskResultModel result = task.result();
        if (result == null) {
            throw new DomainException(ResultCode.NOT_FOUND, "No result for task: " + taskId);
        }
        return result;
    }
```

Reusing `getForClient` keeps the owner-check (load → `NOT_FOUND` on missing task → `NOT_FOUND` on `clientId` mismatch) in exactly one place; `getResult` only adds the "owned task has no result yet" → `NOT_FOUND`. `TaskModel.result()` is the child loaded through the aggregate root, so no repository call is added.

Verify it compiles:

```
mvn -f backend/pom.xml -B -q -DskipTests compile
```

Expect: BUILD SUCCESS.

**Commit (interface + impl together — they form one compilable unit):**

```
git add backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java
git commit -m "feat: add TaskReadAppService.getResult (owner-checked, 404 when no result)"
```

---

## Task 5 — Integration test for the read path (RED → GREEN)

Mirrors `TaskSubmissionIntegrationTest`'s Testcontainers + `@EnabledIf("dockerAvailable")` + `@ActiveProfiles("test")` setup. Seeds rows directly via `JdbcTemplate` (no lifecycle mocks needed — see "Why the integration test seeds via JDBC"). Asserts the three outcomes: owned task with result → DTO; another client → `NOT_FOUND`; owned task without a result → `NOT_FOUND`.

### 5a. The test (RED — `getResult` is already implemented, so this goes GREEN once it compiles, but write and run it now)

Create `backend/src/test/java/com/hireai/task/TaskResultEndpointIntegrationTest.java`:

```java
package com.hireai.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.exception.DomainException;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V5. Drives the read
 * path of {@link TaskReadAppService#getResult}: an owned RESULT_RECEIVED task returns its result;
 * a non-owner gets NOT_FOUND (existence not leaked); an owned task with no result row gets
 * NOT_FOUND (the UI's "pending" signal). Rows are seeded directly via JdbcTemplate — task_results
 * has no append-only trigger, so a plain INSERT is legitimate and keeps the test free of routing /
 * dispatch-token mocks. Each test uses fresh UUIDs so the shared container carries no cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class TaskResultEndpointIntegrationTest {

    /** Skip (do not fail) the whole class when no Docker daemon is reachable. */
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

    @Autowired TaskReadAppService taskReadAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    /** Seed a task in the given status for the given owner; returns the task id. */
    private UUID newTask(UUID clientId, String status) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, category)
                VALUES (?, ?, 'Summarise report', 'Summarise the attached quarterly report',
                        30.00, CAST(? AS jsonb), ?, 'summarisation')""",
                taskId, clientId,
                "{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"valid JSON\"}",
                status);
        return taskId;
    }

    /** Seed the single task_results row for a task. */
    private void newResult(UUID taskId, String agentStatus, String payloadJson, String resultUrl) {
        jdbc.update("""
                INSERT INTO task_results (id, task_id, result_payload, result_url, agent_status, received_at)
                VALUES (?, ?, CAST(? AS jsonb), ?, ?, ?)""",
                UUID.randomUUID(), taskId, payloadJson, resultUrl, agentStatus, Instant.now());
    }

    @Test
    void returnsResultForOwningClient() {
        UUID client = newClient();
        UUID taskId = newTask(client, "RESULT_RECEIVED");
        newResult(taskId, "COMPLETED", "{\"summary\":\"all good\"}", "https://x/y");

        TaskResultModel result = taskReadAppService.getResult(taskId, client);

        assertThat(result.taskId()).isEqualTo(taskId);
        assertThat(result.agentStatus()).isEqualTo("COMPLETED");
        assertThat(result.resultPayloadJson()).contains("all good");
        assertThat(result.resultUrl()).isEqualTo("https://x/y");
    }

    @Test
    void hidesResultFromADifferentClient() {
        UUID owner = newClient();
        UUID other = newClient();
        UUID taskId = newTask(owner, "RESULT_RECEIVED");
        newResult(taskId, "COMPLETED", "{\"summary\":\"all good\"}", null);

        assertThatThrownBy(() -> taskReadAppService.getResult(taskId, other))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void notFoundWhenTaskHasNoResultYet() {
        UUID client = newClient();
        UUID taskId = newTask(client, "EXECUTING"); // no task_results row

        assertThatThrownBy(() -> taskReadAppService.getResult(taskId, client))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void notFoundWhenTaskDoesNotExist() {
        UUID client = newClient();

        assertThatThrownBy(() -> taskReadAppService.getResult(UUID.randomUUID(), client))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).resultCode().name())
                .isEqualTo("NOT_FOUND");
    }
}
```

Run (GREEN on a Docker host; SKIPPED without Docker):

```
mvn -f backend/pom.xml -B test -Dtest=TaskResultEndpointIntegrationTest
```

Expect: PASS (4 tests), or the class reports SKIPPED when no Docker daemon is reachable. If it is skipped locally, rely on Task 7's full run on a Docker host.

**Commit:**

```
git add backend/src/test/java/com/hireai/task/TaskResultEndpointIntegrationTest.java
git commit -m "test: add integration test for owner-scoped task result reads"
```

---

## Task 6 — `TaskController.getResult` endpoint + `@WebMvcTest` (RED → GREEN)

### 6a. Failing `@WebMvcTest` controller test (RED)

Mirrors `AgentCallbackControllerTest`'s `@WebMvcTest` + `@Import(SecurityConfig.class)` + `@WithMockUser` + `@ActiveProfiles("test")` shape. Unlike that controller, `TaskController` depends on `CurrentUserProvider` (and two app services); a `@WebMvcTest` slice does not load the `@Profile("test")` `DevCurrentUserProvider` component, so all three collaborators are supplied as `@MockBean`. The test stubs the current user, stubs `getResult`, and asserts the JSON envelope; a second test stubs a `DomainException(NOT_FOUND)` and asserts HTTP 404 (the global advice is loaded by `@WebMvcTest`).

Create `backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java`:

```java
package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.controller.config.SecurityConfig;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice test for the task result endpoint. Identity comes from {@link CurrentUserProvider}
 * (mocked here — the WebMvcTest slice does not load the test-profile DevCurrentUserProvider). The
 * happy path returns 200 + the WebResult envelope; a NOT_FOUND DomainException maps to HTTP 404 via
 * the global advice loaded by @WebMvcTest.
 */
@WebMvcTest(TaskController.class)
@Import(SecurityConfig.class)
@WithMockUser
@ActiveProfiles("test")
class TaskControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean TaskReadAppService taskReadAppService;
    @MockBean TaskWriteAppService taskWriteAppService;
    @MockBean CurrentUserProvider currentUserProvider;

    @Test
    void returns200WithResultPayloadForOwningClient() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getResult(eq(taskId), eq(clientId)))
                .thenReturn(TaskResultModel.rehydrate(UUID.randomUUID(), taskId, "COMPLETED",
                        "{\"summary\":\"ok\"}", "https://x/y", Instant.parse("2026-06-06T10:15:30Z")));

        mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(taskId.toString()))
                .andExpect(jsonPath("$.data.agentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.resultPayloadJson").value("{\"summary\":\"ok\"}"))
                .andExpect(jsonPath("$.data.resultUrl").value("https://x/y"));
    }

    @Test
    void returns404WhenNoResult() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(currentUserProvider.currentUserId()).thenReturn(clientId);
        when(taskReadAppService.getResult(eq(taskId), eq(clientId)))
                .thenThrow(new DomainException(ResultCode.NOT_FOUND, "No result for task: " + taskId));

        mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
```

Run (RED):

```
mvn -f backend/pom.xml -B test -Dtest=TaskControllerTest
```

Expect: FAIL — the endpoint does not exist yet, so the happy-path request resolves no handler (404) and `$.data.agentStatus` is absent; the test fails on the first unmatched assertion.

### 6b. The endpoint (GREEN)

Edit `backend/src/main/java/com/hireai/controller/biz/task/TaskController.java`.

Add the two imports (alongside the existing controller imports):

```java
import com.hireai.controller.biz.task.converter.TaskResult2DTOConverter;
import com.hireai.controller.biz.task.dto.TaskResultDTO;
```

Add this method immediately after `getById` (before `list`):

```java
    @GetMapping("/{taskId}/result")
    public WebResult<TaskResultDTO> getResult(@PathVariable("taskId") UUID taskId) {
        UUID clientId = currentUser.currentUserId();
        TaskResultDTO dto = TaskResult2DTOConverter.toDTO(readAppService.getResult(taskId, clientId));
        return ok(dto);
    }
```

This mirrors `getById` exactly: identity from `currentUser.currentUserId()` (never the `{taskId}` path), one app-service call, `WebResult` envelope. The existing `@GetMapping("/{id}")` matches only a single path segment, so `/{taskId}/result` is unambiguous.

Run (GREEN):

```
mvn -f backend/pom.xml -B test -Dtest=TaskControllerTest
```

Expect: PASS (2 tests).

**Commit:**

```
git add backend/src/main/java/com/hireai/controller/biz/task/TaskController.java backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java
git commit -m "feat: add GET /api/tasks/{taskId}/result returning WebResult<TaskResultDTO>"
```

---

## Task 7 — Full suite green (regression gate)

No new code. Confirm the whole backend suite still passes (the existing controller/integration tests must stay green, and the new tests run alongside them).

```
mvn -f backend/pom.xml -B test
```

Expect: BUILD SUCCESS. On a Docker host all `*IntegrationTest` classes run (including `TaskResultEndpointIntegrationTest`); without Docker they report SKIPPED and the unit/web-slice tests (incl. `TaskResult2DTOConverterTest` and `TaskControllerTest`) still pass.

If anything fails, fix the implementation (not the tests, unless a test is demonstrably wrong) before proceeding — see `agentic-workers:systematic-debugging`.

**No commit** (verification only). If the working tree is clean, this slice is complete.

---

## Definition of done

- `GET /api/tasks/{taskId}/result` returns `WebResult<TaskResultDTO>`; identity derived from `CurrentUserProvider`, never the path (Hard Invariant #5).
- Owner-check mirrors `getById`/`getForClient`: a non-owner and a missing task both yield `NOT_FOUND` → HTTP 404 (existence not leaked).
- An owned task with no result yet yields `NOT_FOUND` → HTTP 404 (the UI's "pending, keep polling" signal).
- `TaskResultDTO` + `TaskResult2DTOConverter` keep domain types off the boundary; the `TaskResultModel` child is read through the Task aggregate root (no new repository method).
- Tests: converter unit test (2), integration test (4, Docker-gated), `@WebMvcTest` controller test (2, `@ActiveProfiles("test")`); full suite green.
- **No schema change** (reads existing `task_results` from Flyway `V4`).
- All commits conventional (`feat:` / `test:`); no `Co-Authored-By` lines.

## Files added / changed

**Added**
- `backend/src/main/java/com/hireai/controller/biz/task/dto/TaskResultDTO.java`
- `backend/src/main/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverter.java`
- `backend/src/test/java/com/hireai/controller/biz/task/converter/TaskResult2DTOConverterTest.java`
- `backend/src/test/java/com/hireai/task/TaskResultEndpointIntegrationTest.java`
- `backend/src/test/java/com/hireai/controller/biz/task/TaskControllerTest.java`

**Changed**
- `backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java` (+ `getResult` method, Javadoc)
- `backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java` (+ `getResult` impl)
- `backend/src/main/java/com/hireai/controller/biz/task/TaskController.java` (+ `getResult` endpoint)
