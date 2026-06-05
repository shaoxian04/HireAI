# Task Submission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first vertical slice of Module 1 (Task Submission): a client submits a well-defined task with a budget, the budget freezes in escrow against that task, and the task persists as `SUBMITTED` — all atomically.

**Architecture:** New `TaskModel` aggregate (root owns `OutputSpec` value object) following the project's DDD layering (`controller → application → domain ← infrastructure`). The submit use case runs in one `@Transactional` that persists the task and calls the already-tested `WalletWriteAppService.freeze(...)`; a failed freeze rolls the task back, so Hard Invariant #1 (escrow before execution) is enforced by transaction atomicity. Cross-aggregate coordination is application-layer only (app-service → app-service), never a domain cross-import.

**Tech Stack:** Spring Boot 3.3.5, Java 21, JPA/Hibernate 6, Flyway, PostgreSQL (JSONB for `output_spec`), Testcontainers, JUnit 5 + AssertJ. New deps: Lombok and JSpecify (per the `springboot-service` skill conventions).

## Conventions applied (read before starting)

- **App services use interface + `impl/`.** Interface in `application/biz/<aggregate>/` (e.g. `TaskWriteAppService`), implementation in `application/biz/<aggregate>/impl/` (e.g. `TaskWriteAppServiceImpl`). Per `springboot-service`: the **interface** carries `@Validated` and `@NonNull`/validation annotations on parameters; the **impl** carries `@Service`, `@Slf4j`, `@RequiredArgsConstructor`, `@Transactional`. Use `log`, no `var`, descriptive variable names.
- **Domain services stay concrete** (framework-free trivial delegators), matching the existing `WalletFreezeDomainService`.
- **Repository impls stay** in `infrastructure/repository/<aggregate>/` (interface in domain, impl in infrastructure — already the established split).
- **The existing Wallet app services are retrofitted** to interface + `impl/` in Task 2 so the codebase is uniform.
- **Attachments are deferred entirely** in this slice (no table, no model). The design spec listed `TaskAttachmentModel` as "modeled but deferred"; rather than introduce unused code, the whole attachment vertical (table + child entity + upload) lands in its own later slice. This plan creates only `tasks` (+ `output_spec`).
- **Identity** always comes from `CurrentUserProvider` (server-side), never the request body (Invariant #5).
- **All `mvn` commands** run from the repo root against the backend module: `mvn -f backend/pom.xml ...`.

## File structure

**New files:**
```
backend/src/main/java/com/hireai/
  domain/biz/task/enums/OutputFormat.java
  domain/biz/task/enums/TaskStatus.java
  domain/biz/task/model/OutputSpec.java
  domain/biz/task/model/TaskModel.java
  domain/biz/task/info/TaskSubmitInfo.java
  domain/biz/task/service/TaskSubmitDomainService.java
  domain/biz/task/repository/TaskRepository.java
  domain/biz/task/repository/TaskQuery.java
  domain/biz/task/event/TaskSubmittedDomainEvent.java
  application/biz/task/TaskWriteAppService.java
  application/biz/task/TaskReadAppService.java
  application/biz/task/impl/TaskWriteAppServiceImpl.java
  application/biz/task/impl/TaskReadAppServiceImpl.java
  application/biz/wallet/impl/WalletWriteAppServiceImpl.java        (retrofit)
  application/biz/wallet/impl/WalletReadAppServiceImpl.java         (retrofit)
  infrastructure/repository/task/TaskJpaEntity.java
  infrastructure/repository/task/TaskJpaRepository.java
  infrastructure/repository/task/OutputSpecJsonMapper.java
  infrastructure/repository/task/TaskRepositoryImpl.java
  controller/biz/task/TaskController.java
  controller/biz/task/dto/SubmitTaskRequest.java
  controller/biz/task/dto/TaskDTO.java
  controller/biz/task/converter/TaskModel2DTOConverter.java
  resources/db/migration/V2__tasks.sql
backend/src/test/java/com/hireai/
  domain/biz/task/model/OutputSpecTest.java
  domain/biz/task/model/TaskModelTest.java
  task/TaskSubmissionIntegrationTest.java
```

**Modified files:**
```
backend/pom.xml                                                  (add Lombok + JSpecify)
application/biz/wallet/WalletWriteAppService.java                (class → interface)
application/biz/wallet/WalletReadAppService.java                 (class → interface)
application/config/DomainServiceConfig.java                      (register TaskSubmitDomainService bean)
CLAUDE.md                                                        (repository status)
docs/details/data-model.md                                      (tasks table)
```

---

### Task 1: Add Lombok + JSpecify dependencies

**Files:**
- Modify: `backend/pom.xml`

- [ ] **Step 1: Add the two dependencies**

In `backend/pom.xml`, inside `<dependencies>`, add after the `spring-boot-starter-security` dependency (before the Flyway block):

```xml
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.jspecify</groupId>
            <artifactId>jspecify</artifactId>
            <version>1.0.0</version>
        </dependency>
```

(Lombok's version is managed by the Spring Boot parent; JSpecify is not, so it is pinned.)

- [ ] **Step 2: Verify the project still compiles**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS` (Lombok annotation processing is picked up automatically; nothing uses it yet).

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml
git commit -m "chore(backend): add Lombok and JSpecify dependencies"
```

---

### Task 2: Retrofit Wallet app services to interface + impl/

Splits the two concrete Wallet app services into an interface (`@Validated`, `@NonNull` params) plus an implementation under `impl/` (`@Service @Slf4j @RequiredArgsConstructor @Transactional`). Behaviour is unchanged; existing tests must stay green.

**Files:**
- Modify: `backend/src/main/java/com/hireai/application/biz/wallet/WalletWriteAppService.java` (becomes interface)
- Modify: `backend/src/main/java/com/hireai/application/biz/wallet/WalletReadAppService.java` (becomes interface)
- Create: `backend/src/main/java/com/hireai/application/biz/wallet/impl/WalletWriteAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/application/biz/wallet/impl/WalletReadAppServiceImpl.java`

- [ ] **Step 1: Replace `WalletWriteAppService.java` with the interface**

Overwrite the file so its full contents are:

```java
package com.hireai.application.biz.wallet;

import com.hireai.domain.shared.model.Money;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates wallet WRITE use cases. Transactional; invokes per-transition
 * domain services and persists through the repository INTERFACE. Returns only the
 * aggregate ID — callers re-read full state via {@link WalletReadAppService}.
 */
@Validated
public interface WalletWriteAppService {

    UUID topUp(@NonNull UUID userId, @NonNull Money amount, @NonNull String correlationId);

    UUID freeze(@NonNull UUID userId, @NonNull Money amount, @NonNull UUID taskId, @NonNull String correlationId);
}
```

- [ ] **Step 2: Create `impl/WalletWriteAppServiceImpl.java`**

```java
package com.hireai.application.biz.wallet.impl;

import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.biz.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class WalletWriteAppServiceImpl implements WalletWriteAppService {

    private final WalletRepository walletRepository;
    private final WalletTopUpDomainService topUpDomainService;
    private final WalletFreezeDomainService freezeDomainService;

    @Override
    public UUID topUp(UUID userId, Money amount, String correlationId) {
        WalletModel wallet = loadOrOpen(userId);
        topUpDomainService.topUp(wallet, amount, correlationId);
        return walletRepository.save(wallet).id();
    }

    @Override
    public UUID freeze(UUID userId, Money amount, UUID taskId, String correlationId) {
        WalletModel wallet = requireWallet(userId);
        freezeDomainService.freeze(wallet, amount, taskId, correlationId);
        return walletRepository.save(wallet).id();
    }

    private WalletModel loadOrOpen(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> walletRepository.save(WalletModel.openFor(userId)));
    }

    private WalletModel requireWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }
}
```

- [ ] **Step 3: Replace `WalletReadAppService.java` with the interface**

Overwrite the file so its full contents are:

```java
package com.hireai.application.biz.wallet;

import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletLedgerQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates wallet READ use cases. Returns domain models to the controller,
 * which converts them to DTOs. Read-only transactions; cache-safe.
 */
@Validated
public interface WalletReadAppService {

    WalletModel getByUserId(@NonNull UUID userId);

    List<LedgerEntryModel> getLedger(@NonNull UUID userId, @NonNull WalletLedgerQuery query);
}
```

- [ ] **Step 4: Create `impl/WalletReadAppServiceImpl.java`**

```java
package com.hireai.application.biz.wallet.impl;

import com.hireai.application.biz.wallet.WalletReadAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.biz.wallet.repository.WalletLedgerQuery;
import com.hireai.domain.biz.wallet.repository.WalletRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletReadAppServiceImpl implements WalletReadAppService {

    private final WalletRepository walletRepository;

    @Override
    public WalletModel getByUserId(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No wallet for user " + userId));
    }

    @Override
    public List<LedgerEntryModel> getLedger(UUID userId, WalletLedgerQuery query) {
        WalletModel wallet = getByUserId(userId);
        return walletRepository.findLedger(wallet.id(), query);
    }
}
```

- [ ] **Step 5: Run the existing tests to confirm no regression**

Run: `mvn -f backend/pom.xml -B test`
Expected: `BUILD SUCCESS`. `WalletController`, `WalletLedgerIntegrationTest`, etc. resolve the app services by interface; injection still works. Without Docker: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 3`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/wallet
git commit -m "refactor(backend): split Wallet app services into interface + impl"
```

---

### Task 3: Task enums

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/enums/OutputFormat.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/enums/TaskStatus.java`

- [ ] **Step 1: Create `OutputFormat.java`**

```java
package com.hireai.domain.biz.task.enums;

/** The declared shape of an Agent's deliverable, part of the binding output contract. */
public enum OutputFormat {
    TEXT,
    JSON,
    FILE
}
```

- [ ] **Step 2: Create `TaskStatus.java`**

```java
package com.hireai.domain.biz.task.enums;

/**
 * Full task lifecycle. Only {@link #SUBMITTED} is reachable in the current slice;
 * the remaining states are declared for schema forward-compatibility and land with
 * the routing, validation, dispute, and settlement modules.
 */
public enum TaskStatus {
    SUBMITTED,
    ROUTING,
    IN_PROGRESS,
    SUBMITTED_FOR_REVIEW,
    VALIDATING,
    ACCEPTED,
    REJECTED,
    DISPUTED,
    SETTLED,
    CANCELLED
}
```

- [ ] **Step 3: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task/enums
git commit -m "feat(backend): add Task output-format and status enums"
```

---

### Task 4: OutputSpec value object (TDD)

**Files:**
- Test: `backend/src/test/java/com/hireai/domain/biz/task/model/OutputSpecTest.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/model/OutputSpec.java`

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputSpecTest {

    @Test
    void buildsWithFormatAndOptionalFields() {
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "must be valid JSON");
        assertThat(spec.format()).isEqualTo(OutputFormat.JSON);
        assertThat(spec.schema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.acceptanceCriteria()).isEqualTo("must be valid JSON");
    }

    @Test
    void rejectsNullFormat() {
        assertThatThrownBy(() -> new OutputSpec(null, null, null))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -Dtest=OutputSpecTest`
Expected: FAIL — compilation error, `OutputSpec` does not exist.

- [ ] **Step 3: Create `OutputSpec.java`**

```java
package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.shared.exception.DomainException;

/**
 * The binding output contract a client declares for a task (Hard Invariant #4).
 * Persisted faithfully and immutably; later consumed by automated validation and
 * by dispute arbitration. {@code format} is required; {@code schema} and
 * {@code acceptanceCriteria} are optional free-form fields.
 */
public record OutputSpec(OutputFormat format, String schema, String acceptanceCriteria) {

    public OutputSpec {
        if (format == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Output spec format is required");
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -Dtest=OutputSpecTest`
Expected: PASS — `Tests run: 2, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task/model/OutputSpec.java backend/src/test/java/com/hireai/domain/biz/task/model/OutputSpecTest.java
git commit -m "feat(backend): add OutputSpec value object"
```

---

### Task 5: TaskModel aggregate root (TDD)

**Files:**
- Test: `backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTest.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`

- [ ] **Step 1: Write the failing test**

```java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.TEXT, null, "concise summary");
    }

    @Test
    void submitBuildsSubmittedTask() {
        UUID clientId = UUID.randomUUID();
        TaskModel task = TaskModel.submit(clientId, "Summarise doc", "Summarise the attached report",
                Money.of("25.00"), spec());

        assertThat(task.id()).isNotNull();
        assertThat(task.clientId()).isEqualTo(clientId);
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(task.budget()).isEqualTo(Money.of("25.00"));
        assertThat(task.outputSpec()).isEqualTo(spec());
        assertThat(task.createdAt()).isNotNull();
    }

    @Test
    void trimsTitleAndDescription() {
        TaskModel task = TaskModel.submit(UUID.randomUUID(), "  title  ", "  desc  ", Money.of("5.00"), spec());
        assertThat(task.title()).isEqualTo("title");
        assertThat(task.description()).isEqualTo("desc");
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "  ", "desc", Money.of("5.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankDescription() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "", Money.of("5.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveBudget() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("0.00"), spec()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> TaskModel.submit(UUID.randomUUID(), "title", "desc", Money.of("5.00"), null))
                .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskModelTest`
Expected: FAIL — compilation error, `TaskModel` does not exist.

- [ ] **Step 3: Create `TaskModel.java`**

```java
package com.hireai.domain.biz.task.model;

import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Task aggregate root. A task is the unit of work a client submits; its budget is
 * frozen in escrow at submission. Behaviour (the submit transition + its invariants)
 * lives here, not in setters. The aggregate is framework-free.
 */
public final class TaskModel {

    private final UUID id;
    private final UUID clientId;
    private final String title;
    private final String description;
    private final Money budget;
    private final OutputSpec outputSpec;
    private final TaskStatus status;
    private final Instant createdAt;

    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, TaskStatus status, Instant createdAt) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Factory for the SUBMIT transition: enforces invariants and creates a SUBMITTED task. */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec) {
        requirePresent(clientId, "client id");
        requireText(title, "title");
        requireText(description, "description");
        requirePositive(budget);
        requirePresent(outputSpec, "output spec");
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, TaskStatus.SUBMITTED, Instant.now());
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Budget must be positive");
        }
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public String title() { return title; }
    public String description() { return description; }
    public Money budget() { return budget; }
    public OutputSpec outputSpec() { return outputSpec; }
    public TaskStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -f backend/pom.xml -B test -Dtest=TaskModelTest`
Expected: PASS — `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java backend/src/test/java/com/hireai/domain/biz/task/model/TaskModelTest.java
git commit -m "feat(backend): add Task aggregate root with submit transition"
```

---

### Task 6: TaskSubmitInfo carrier, domain service, event, bean registration

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/info/TaskSubmitInfo.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/service/TaskSubmitDomainService.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/event/TaskSubmittedDomainEvent.java`
- Modify: `backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java`

- [ ] **Step 1: Create `TaskSubmitInfo.java`**

```java
package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Domain-layer carrier for the submit use case. Assembled by the controller from a
 * validated request plus the server-side client id; passed to the application layer.
 */
public record TaskSubmitInfo(UUID clientId, String title, String description,
                             Money budget, OutputSpec outputSpec) {
}
```

- [ ] **Step 2: Create `TaskSubmitDomainService.java`**

```java
package com.hireai.domain.biz.task.service;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;

/**
 * Domain service for the task SUBMIT state transition. Stateless and framework-free;
 * delegates to the aggregate factory, which owns the invariants.
 */
public class TaskSubmitDomainService {

    public TaskModel submit(TaskSubmitInfo info) {
        return TaskModel.submit(info.clientId(), info.title(), info.description(),
                info.budget(), info.outputSpec());
    }
}
```

- [ ] **Step 3: Create `TaskSubmittedDomainEvent.java`**

```java
package com.hireai.domain.biz.task.event;

import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a task is submitted and its budget frozen. No consumer yet; this is
 * the seam the routing module (Module 3) will subscribe to in order to start dispatch.
 */
public record TaskSubmittedDomainEvent(UUID taskId, UUID clientId, Money budget, Instant occurredAt) {
}
```

- [ ] **Step 4: Register the domain service bean**

In `backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java`, add the import and a bean method. The file becomes:

```java
package com.hireai.application.config;

import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import com.hireai.domain.biz.wallet.service.WalletFreezeDomainService;
import com.hireai.domain.biz.wallet.service.WalletTopUpDomainService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers framework-free domain services as Spring beans. Domain services
 * carry no Spring annotations (the domain layer has zero framework imports), so
 * they are wired here in the application layer instead.
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public WalletTopUpDomainService walletTopUpDomainService() {
        return new WalletTopUpDomainService();
    }

    @Bean
    public WalletFreezeDomainService walletFreezeDomainService() {
        return new WalletFreezeDomainService();
    }

    @Bean
    public TaskSubmitDomainService taskSubmitDomainService() {
        return new TaskSubmitDomainService();
    }
}
```

- [ ] **Step 5: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task/info backend/src/main/java/com/hireai/domain/biz/task/service backend/src/main/java/com/hireai/domain/biz/task/event backend/src/main/java/com/hireai/application/config/DomainServiceConfig.java
git commit -m "feat(backend): add Task submit info, domain service, event"
```

---

### Task 7: TaskRepository interface + TaskQuery

**Files:**
- Create: `backend/src/main/java/com/hireai/domain/biz/task/repository/TaskRepository.java`
- Create: `backend/src/main/java/com/hireai/domain/biz/task/repository/TaskQuery.java`

- [ ] **Step 1: Create `TaskQuery.java`**

```java
package com.hireai.domain.biz.task.repository;

/**
 * Query object for paginated task reads. Page is zero-based; size is clamped to a
 * sane range. Mirrors WalletLedgerQuery so the read paths look the same.
 */
public record TaskQuery(int page, int size) {

    public TaskQuery {
        if (page < 0) {
            page = 0;
        }
        if (size < 1 || size > 100) {
            size = 50;
        }
    }

    public static TaskQuery firstPage() {
        return new TaskQuery(0, 50);
    }
}
```

- [ ] **Step 2: Create `TaskRepository.java`**

```java
package com.hireai.domain.biz.task.repository;

import com.hireai.domain.biz.task.model.TaskModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Task aggregate. One repository per aggregate root.
 * The interface lives in the domain layer and carries no framework imports; the JPA
 * implementation lives in infrastructure.
 */
public interface TaskRepository {

    TaskModel save(TaskModel task);

    Optional<TaskModel> findById(UUID taskId);

    List<TaskModel> findByClientId(UUID clientId, TaskQuery query);
}
```

- [ ] **Step 3: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/hireai/domain/biz/task/repository
git commit -m "feat(backend): add Task repository contract and query object"
```

---

### Task 8: Flyway V2 migration (tasks table)

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__tasks.sql`

- [ ] **Step 1: Create `V2__tasks.sql`**

```sql
-- V2: Tasks. A task is the unit of work a client submits; its budget is frozen in
-- escrow at submission (see Wallet/ledger in V1). output_spec is the binding output
-- contract used later by validation and arbitration; stored as JSONB so the contract
-- shape can evolve without a migration. Only SUBMITTED is reachable in the current
-- slice; the rest of the status set is declared for forward-compatibility.

CREATE TABLE tasks (
    id            UUID PRIMARY KEY,
    client_id     UUID NOT NULL REFERENCES users (id),
    title         TEXT NOT NULL,
    description   TEXT NOT NULL,
    budget        NUMERIC(14, 2) NOT NULL CHECK (budget > 0),
    output_spec   JSONB NOT NULL,
    status        TEXT NOT NULL CHECK (status IN (
                      'SUBMITTED', 'ROUTING', 'IN_PROGRESS', 'SUBMITTED_FOR_REVIEW',
                      'VALIDATING', 'ACCEPTED', 'REJECTED', 'DISPUTED', 'SETTLED', 'CANCELLED')),
    gmt_create    TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tasks_client_created ON tasks (client_id, gmt_create DESC);
```

- [ ] **Step 2: Commit** (the migration is verified by the integration test in Task 13)

```bash
git add backend/src/main/resources/db/migration/V2__tasks.sql
git commit -m "feat(backend): add V2 migration for tasks table"
```

---

### Task 9: Infrastructure — JPA entity, repository, JSON mapper, repository impl

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaEntity.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskJpaRepository.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/OutputSpecJsonMapper.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/repository/task/TaskRepositoryImpl.java`

- [ ] **Step 1: Create `TaskJpaEntity.java`**

```java
package com.hireai.infrastructure.repository.task;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA persistence entity for a task. Separate from the domain {@code TaskModel} so the
 * domain stays framework-free. {@code output_spec} is stored as JSONB; the repository
 * impl serialises the {@code OutputSpec} value object to/from JSON.
 */
@Entity
@Table(name = "tasks")
public class TaskJpaEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "budget", nullable = false)
    private BigDecimal budget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_spec", columnDefinition = "jsonb", nullable = false)
    private String outputSpec;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "gmt_create", nullable = false)
    private Instant gmtCreate;

    protected TaskJpaEntity() {
    }

    public TaskJpaEntity(UUID id, UUID clientId, String title, String description,
                         BigDecimal budget, String outputSpec, String status, Instant gmtCreate) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.status = status;
        this.gmtCreate = gmtCreate;
    }

    public UUID getId() { return id; }
    public UUID getClientId() { return clientId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public BigDecimal getBudget() { return budget; }
    public String getOutputSpec() { return outputSpec; }
    public String getStatus() { return status; }
    public Instant getGmtCreate() { return gmtCreate; }
}
```

- [ ] **Step 2: Create `TaskJpaRepository.java`**

```java
package com.hireai.infrastructure.repository.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for task rows. Internal to infrastructure. */
public interface TaskJpaRepository extends JpaRepository<TaskJpaEntity, UUID> {

    List<TaskJpaEntity> findByClientIdOrderByGmtCreateDesc(UUID clientId, Pageable pageable);
}
```

- [ ] **Step 3: Create `OutputSpecJsonMapper.java`**

```java
package com.hireai.infrastructure.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.stereotype.Component;

/**
 * Serialises the {@code OutputSpec} value object to/from the JSONB column. Jackson
 * handles the record natively (canonical constructor), so the domain stays annotation-free.
 */
@Component
public class OutputSpecJsonMapper {

    private final ObjectMapper objectMapper;

    public OutputSpecJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(OutputSpec outputSpec) {
        try {
            return objectMapper.writeValueAsString(outputSpec);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to serialise output spec");
        }
    }

    public OutputSpec fromJson(String json) {
        try {
            return objectMapper.readValue(json, OutputSpec.class);
        } catch (Exception exception) {
            throw new DomainException(ResultCode.INTERNAL_ERROR, "Failed to read output spec");
        }
    }
}
```

- [ ] **Step 4: Create `TaskRepositoryImpl.java`**

```java
package com.hireai.infrastructure.repository.task;

import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link TaskRepository}. Maps
 * {@code TaskModel} &lt;-&gt; JPA entity and serialises the output spec via
 * {@link OutputSpecJsonMapper}.
 */
@Repository
public class TaskRepositoryImpl implements TaskRepository {

    private final TaskJpaRepository taskJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public TaskRepositoryImpl(TaskJpaRepository taskJpa, OutputSpecJsonMapper outputSpecJsonMapper) {
        this.taskJpa = taskJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public TaskModel save(TaskModel task) {
        taskJpa.save(new TaskJpaEntity(
                task.id(), task.clientId(), task.title(), task.description(),
                task.budget().value(), outputSpecJsonMapper.toJson(task.outputSpec()),
                task.status().name(), task.createdAt()));
        return task;
    }

    @Override
    public Optional<TaskModel> findById(UUID taskId) {
        return taskJpa.findById(taskId).map(this::toModel);
    }

    @Override
    public List<TaskModel> findByClientId(UUID clientId, TaskQuery query) {
        return taskJpa.findByClientIdOrderByGmtCreateDesc(
                        clientId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    private TaskModel toModel(TaskJpaEntity entity) {
        return new TaskModel(
                entity.getId(), entity.getClientId(), entity.getTitle(), entity.getDescription(),
                Money.of(entity.getBudget()), outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                TaskStatus.valueOf(entity.getStatus()), entity.getGmtCreate());
    }
}
```

- [ ] **Step 5: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/infrastructure/repository/task
git commit -m "feat(backend): add Task JPA persistence (entity, repo, JSONB output spec)"
```

---

### Task 10: Application — Task app services (interface + impl/)

**Files:**
- Create: `backend/src/main/java/com/hireai/application/biz/task/TaskWriteAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/task/TaskReadAppService.java`
- Create: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskWriteAppServiceImpl.java`
- Create: `backend/src/main/java/com/hireai/application/biz/task/impl/TaskReadAppServiceImpl.java`

- [ ] **Step 1: Create `TaskWriteAppService.java` (interface)**

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates task WRITE use cases. The submit use case enforces Hard Invariant #1
 * (escrow before execution): the task is persisted and its budget frozen in the SAME
 * transaction, so a failed freeze rolls the task back — there is no task without a
 * successful escrow freeze. Returns only the task id; callers re-read via the read service.
 */
@Validated
public interface TaskWriteAppService {

    UUID submit(@NonNull TaskSubmitInfo taskSubmitInfo);
}
```

- [ ] **Step 2: Create `TaskReadAppService.java` (interface)**

```java
package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates task READ use cases. Enforces Hard Invariant #5 (server-side identity +
 * ownership): a task is only returned to the client that owns it; otherwise NOT_FOUND,
 * so existence is not leaked across clients.
 */
@Validated
public interface TaskReadAppService {

    TaskModel getForClient(@NonNull UUID taskId, @NonNull UUID clientId);

    List<TaskModel> listForClient(@NonNull UUID clientId, @NonNull TaskQuery query);
}
```

- [ ] **Step 3: Create `impl/TaskWriteAppServiceImpl.java`**

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskWriteAppServiceImpl implements TaskWriteAppService {

    private final TaskRepository taskRepository;
    private final TaskSubmitDomainService taskSubmitDomainService;
    private final WalletWriteAppService walletWriteAppService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public UUID submit(TaskSubmitInfo taskSubmitInfo) {
        String correlationId = UUID.randomUUID().toString();
        TaskModel task = taskSubmitDomainService.submit(taskSubmitInfo);
        UUID taskId = taskRepository.save(task).id();
        walletWriteAppService.freeze(taskSubmitInfo.clientId(), taskSubmitInfo.budget(), taskId, correlationId);
        eventPublisher.publishEvent(new TaskSubmittedDomainEvent(
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget(), task.createdAt()));
        log.info("Task {} submitted by client {}; budget {} frozen in escrow",
                taskId, taskSubmitInfo.clientId(), taskSubmitInfo.budget());
        return taskId;
    }
}
```

- [ ] **Step 4: Create `impl/TaskReadAppServiceImpl.java`**

```java
package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskReadAppServiceImpl implements TaskReadAppService {

    private final TaskRepository taskRepository;

    @Override
    public TaskModel getForClient(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findById(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

    @Override
    public List<TaskModel> listForClient(UUID clientId, TaskQuery query) {
        return taskRepository.findByClientId(clientId, query);
    }
}
```

- [ ] **Step 5: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hireai/application/biz/task
git commit -m "feat(backend): add Task app services (interface + impl) with atomic escrow freeze"
```

---

### Task 11: Controller DTOs + converter

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/task/dto/SubmitTaskRequest.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/task/dto/TaskDTO.java`
- Create: `backend/src/main/java/com/hireai/controller/biz/task/converter/TaskModel2DTOConverter.java`

- [ ] **Step 1: Create `SubmitTaskRequest.java`**

```java
package com.hireai.controller.biz.task.dto;

import com.hireai.domain.biz.task.enums.OutputFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Inbound HTTP DTO for submitting a task. Bean Validation at the boundary. */
public record SubmitTaskRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull
        @DecimalMin(value = "0.01", message = "budget must be positive")
        @Digits(integer = 12, fraction = 2, message = "budget must have at most 2 decimal places")
        BigDecimal budget,
        @NotNull @Valid OutputSpecRequest outputSpec
) {

    public record OutputSpecRequest(
            @NotNull OutputFormat format,
            @Size(max = 5000) String schema,
            @Size(max = 5000) String acceptanceCriteria
    ) {
    }
}
```

- [ ] **Step 2: Create `TaskDTO.java`**

```java
package com.hireai.controller.biz.task.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound HTTP DTO for a task. No domain types leak across the boundary. */
public record TaskDTO(
        UUID id,
        UUID clientId,
        String title,
        String description,
        BigDecimal budget,
        String status,
        OutputSpecDTO outputSpec,
        Instant createdAt
) {

    public record OutputSpecDTO(String format, String schema, String acceptanceCriteria) {
    }
}
```

- [ ] **Step 3: Create `TaskModel2DTOConverter.java`**

```java
package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;

/**
 * Explicit, hand-written converter from the Task domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 */
public final class TaskModel2DTOConverter {

    private TaskModel2DTOConverter() {
    }

    public static TaskDTO toDTO(TaskModel task) {
        OutputSpec spec = task.outputSpec();
        return new TaskDTO(
                task.id(),
                task.clientId(),
                task.title(),
                task.description(),
                task.budget().value(),
                task.status().name(),
                new TaskDTO.OutputSpecDTO(spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                task.createdAt());
    }
}
```

- [ ] **Step 4: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/biz/task/dto backend/src/main/java/com/hireai/controller/biz/task/converter
git commit -m "feat(backend): add Task request/response DTOs and converter"
```

---

### Task 12: TaskController

**Files:**
- Create: `backend/src/main/java/com/hireai/controller/biz/task/TaskController.java`

- [ ] **Step 1: Create `TaskController.java`**

```java
package com.hireai.controller.biz.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.controller.base.BaseController;
import com.hireai.controller.base.WebResult;
import com.hireai.controller.biz.task.converter.TaskModel2DTOConverter;
import com.hireai.controller.biz.task.dto.SubmitTaskRequest;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.config.CurrentUserProvider;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.repository.TaskQuery;
import com.hireai.domain.shared.model.Money;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Task HTTP surface. Thin: validate the request, resolve identity server-side, build the
 * domain carrier, call one app service, wrap the result. Identity comes from
 * {@link CurrentUserProvider} (the JWT principal once auth lands) — never from path or body.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController extends BaseController {

    private final TaskWriteAppService writeAppService;
    private final TaskReadAppService readAppService;
    private final CurrentUserProvider currentUser;

    public TaskController(TaskWriteAppService writeAppService,
                          TaskReadAppService readAppService,
                          CurrentUserProvider currentUser) {
        this.writeAppService = writeAppService;
        this.readAppService = readAppService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public WebResult<TaskDTO> submit(@Valid @RequestBody SubmitTaskRequest request) {
        UUID clientId = currentUser.currentUserId();
        SubmitTaskRequest.OutputSpecRequest specRequest = request.outputSpec();
        TaskSubmitInfo info = new TaskSubmitInfo(
                clientId,
                request.title(),
                request.description(),
                Money.of(request.budget()),
                new OutputSpec(specRequest.format(), specRequest.schema(), specRequest.acceptanceCriteria()));
        UUID taskId = writeAppService.submit(info);
        TaskDTO dto = TaskModel2DTOConverter.toDTO(readAppService.getForClient(taskId, clientId));
        return ok(dto);
    }

    @GetMapping("/{id}")
    public WebResult<TaskDTO> getById(@PathVariable("id") UUID id) {
        UUID clientId = currentUser.currentUserId();
        TaskDTO dto = TaskModel2DTOConverter.toDTO(readAppService.getForClient(id, clientId));
        return ok(dto);
    }

    @GetMapping
    public WebResult<List<TaskDTO>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        UUID clientId = currentUser.currentUserId();
        List<TaskDTO> tasks = readAppService.listForClient(clientId, new TaskQuery(page, size))
                .stream()
                .map(TaskModel2DTOConverter::toDTO)
                .toList();
        return ok(tasks);
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn -f backend/pom.xml -q -B compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/hireai/controller/biz/task/TaskController.java
git commit -m "feat(backend): add TaskController (submit, get, list)"
```

---

### Task 13: Integration test (Testcontainers) + full suite

Proves the whole slice end-to-end against a real Postgres with Flyway V1+V2 applied: persistence, the atomic escrow freeze, the rollback on insufficient balance, and the JSONB round-trip.

**Files:**
- Create: `backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java`

- [ ] **Step 1: Create `TaskSubmissionIntegrationTest.java`**

```java
package com.hireai.task;

import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.wallet.WalletReadAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1+V2.
 * Verifies the Task submission slice end-to-end: persistence, the atomic escrow freeze
 * (Hard Invariant #1), rollback on insufficient balance, and the output-spec JSONB
 * round-trip. Each test creates its own client so the shared container carries no
 * cross-test state.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
class TaskSubmissionIntegrationTest {

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

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired TaskReadAppService taskReadAppService;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    private TaskSubmitInfo info(UUID clientId, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"));
    }

    @Test
    void submitPersistsTaskAndFreezesEscrowAtomically() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID taskId = taskWriteAppService.submit(info(client, "30.00"));

        TaskModel task = taskReadAppService.getForClient(taskId, client);
        assertThat(task.status()).isEqualTo(TaskStatus.SUBMITTED);
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("70.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.of("30.00"));

        Integer frozen = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE related_task_id = ? AND entry_type = 'ESCROW_FREEZE'",
                Integer.class, taskId);
        assertThat(frozen).isEqualTo(1);
    }

    @Test
    void insufficientBalanceRollsBackTheTask() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("10.00"), "seed");

        assertThatThrownBy(() -> taskWriteAppService.submit(info(client, "50.00")))
                .isInstanceOf(DomainException.class);

        Integer tasks = jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, client);
        assertThat(tasks).isZero();
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("10.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.ZERO);
    }

    @Test
    void outputSpecRoundTripsThroughJsonb() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID taskId = taskWriteAppService.submit(info(client, "20.00"));

        OutputSpec spec = taskReadAppService.getForClient(taskId, client).outputSpec();
        assertThat(spec.format()).isEqualTo(OutputFormat.JSON);
        assertThat(spec.schema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(spec.acceptanceCriteria()).isEqualTo("valid JSON summary");
    }
}
```

- [ ] **Step 2: Run the full suite**

Run: `mvn -f backend/pom.xml -B test`
Expected (with Docker running): `BUILD SUCCESS`, all unit tests plus the 3 new + 3 existing integration tests pass.
Expected (no Docker): `BUILD SUCCESS` with the 6 integration tests **skipped** (`Skipped: 6`), all unit tests pass. Either outcome is a pass for this plan.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/hireai/task/TaskSubmissionIntegrationTest.java
git commit -m "test(backend): add Task submission integration test (atomic escrow, rollback, JSONB)"
```

---

### Task 14: Update docs

**Files:**
- Modify: `CLAUDE.md` (Repository status paragraph)
- Modify: `docs/details/data-model.md` (add tasks table)

- [ ] **Step 1: Update CLAUDE.md repository status**

In `CLAUDE.md`, in the `backend/` bullet under "Repository status", append a sentence noting the Task aggregate. Change the existing backend bullet so it reads (adjust to match current wording, keeping the Wallet sentence):

> `backend/` — Spring Boot (Java 21), DDD bounded contexts. **Scaffolded**: base classes, config, the **Wallet** aggregate (top-up + escrow freeze, append-only ledger, Flyway `V1`), and the **Task** aggregate (submit + atomic escrow freeze + binding `output_spec`, Flyway `V2`). Other contexts pending.

- [ ] **Step 2: Update data-model.md**

Open `docs/details/data-model.md`, find the section describing the Wallet/ledger tables, and add a `tasks` subsection after it:

```markdown
### tasks

The unit of work a client submits. Its budget is frozen in escrow at submission
(atomic with the row insert — see Hard Invariant #1). `output_spec` is the binding
output contract (Invariant #4), stored as JSONB so its shape can evolve without a
migration. Only `SUBMITTED` is reachable today; the rest of the status set is declared
for forward-compatibility.

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| client_id | UUID FK → users | the submitting client (server-side identity) |
| title | TEXT NOT NULL | |
| description | TEXT NOT NULL | |
| budget | NUMERIC(14,2) | CHECK > 0; frozen in escrow at submit |
| output_spec | JSONB NOT NULL | `{ format, schema, acceptanceCriteria }` |
| status | TEXT | CHECK over the task lifecycle set |
| gmt_create / gmt_modified | TIMESTAMPTZ | |

Index: `(client_id, gmt_create DESC)` for the client's task list.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md docs/details/data-model.md
git commit -m "docs: record Task aggregate in repository status and data model"
```

---

## Self-review

**Spec coverage:**
- Aggregate boundary (TaskModel + OutputSpec) → Tasks 4, 5. ✓
- Architectural decision (Option A, synchronous app-service orchestration, atomic freeze) → Task 10 (`TaskWriteAppServiceImpl.submit`). ✓
- Layer-by-layer slice → Tasks 5–12. ✓
- Invariant #1 (escrow before execution, atomic) → Task 10 + integration test Task 13 (`insufficientBalanceRollsBackTheTask`). ✓
- Invariant #4 (binding output spec, JSONB) → Tasks 4, 9, 13 (`outputSpecRoundTripsThroughJsonb`). ✓
- Invariant #5 (server-side identity + ownership) → Task 10 (`getForClient`), Task 12 (controller uses `CurrentUserProvider`). ✓
- Error handling (insufficient balance, bean validation) → existing `GlobalExceptionConfiguration` maps `DomainException` / `MethodArgumentNotValidException`; no new handler needed. ✓
- Demo flow (topup → submit → escrow/ledger visible) → endpoints in Task 12 + existing Wallet endpoints. ✓
- Testing (unit + the crown-jewel rollback integration test) → Tasks 4, 5, 13. ✓
- **Deviation from spec:** attachments deferred entirely (no table/model) — documented in Conventions. Acceptable: spec marked them deferred; avoids dead code.

**Placeholder scan:** No TBD/TODO/"handle errors appropriately"; every code step has complete code; every command has expected output. ✓

**Type consistency:** `TaskModel.submit(UUID, String, String, Money, OutputSpec)` is called identically in `TaskSubmitDomainService` (Task 6), tests (Task 5), and is the only construction path. `TaskSubmitInfo(clientId, title, description, budget, outputSpec)` matches its use in the controller (Task 12), domain service (Task 6), and tests (Task 13). `TaskRepository.{save,findById,findByClientId}` defined in Task 7 and implemented identically in Task 9. App-service interfaces (Task 10) match impl signatures and the controller/test call sites. `OutputSpecJsonMapper.{toJson,fromJson}` defined and used only in Task 9. Wallet interfaces (Task 2) match the existing controller's calls (`topUp`, `freeze`, `getByUserId`, `getLedger`). ✓
