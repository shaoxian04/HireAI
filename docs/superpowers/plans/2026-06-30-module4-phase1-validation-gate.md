# Module 4 Phase 1 — Validation Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce Hard Invariant #4 at runtime — automatically validate every agent result against the task's frozen `output_spec` before any client sees it, moving the task to `PENDING_REVIEW` (pass) or `SPEC_VIOLATION` + full refund (fail).

**Architecture:** Populate the reserved `adjudication` subdomain with a `ValidationReport` aggregate and a framework-free `ValidationDomainService` that runs deterministic structural checks. JSON-Schema validation (which needs a third-party lib the domain may not carry) sits behind a domain **port** (`SchemaValidator`) implemented in `hireai-infrastructure`. A `ValidationAppService` runs the gate synchronously inside the existing agent-callback transaction; on failure it reuses the existing settlement refund path.

**Tech Stack:** Java 21, Spring Boot 3.3.5, COLA multi-module reactor, PostgreSQL + Flyway, Hibernate (`ddl-auto: validate`), `com.networknt:json-schema-validator`, JUnit 5 + Testcontainers.

## Global Constraints

- **Framework-free domain.** `hireai-domain` depends only on `hireai-utility` (+ jspecify). No Spring, no Jackson, no networknt in domain. Tech capabilities (JSON parse / JSON-Schema) enter the domain only through a domain-defined **port** interface, implemented in `hireai-infrastructure`.
- **Immutability.** Aggregates are immutable; transitions return new copies (`TaskModel` uses `copyWith`). Never mutate in place.
- **Service layering.** App services = interface + `impl/` (`@Service`). Domain services are framework-free and wired as `@Bean` in `com.hireai.application.config.DomainServiceConfig`.
- **Migrations.** Additive only; next free version is **`V16`**. Never edit an applied migration. Money is `NUMERIC(14,2)`. Every table carries `gmt_create` / `gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now()`.
- **Cross-context references are soft.** Like `settlements` (V14), the `validation_reports.task_id` is a plain `UUID NOT NULL` (no `REFERENCES`) — the adjudication context references Task by id only.
- **Invariant #4.** Validation runs before the client sees a result: a `SPEC_VIOLATION` result never becomes client-visible and auto-refunds.
- **Inv #3 / money path.** Refund on `SPEC_VIOLATION` reuses the existing deterministic `SettlementWriteAppService.settleRejected(...)` (full refund). No new money math.
- **Testing.** TDD per task. Integration tests (`*IntegrationTest`) use Testcontainers and auto-skip without Docker — never fail the build. Keep the suite green at every task.

**Out of scope for this plan (→ Plan 2 / later phases):** the bounded same-agent retry, the `review_deadline` auto-accept sweeper, `tasks.validation_attempts` / `task_results.attempt_no` retry plumbing, disputes/arbitration (Phase 2/3). To avoid a second Phase-1 migration, `validation_reports` carries an `attempt_no` column now (always `1` in this plan; Plan 2 increments it).

---

## File structure

**New (domain — `hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/`):**
- `enums/Verdict.java` — `PASS` / `FAIL`.
- `model/CheckResult.java` — VO `(String rule, boolean passed, String detail)`.
- `model/ValidationReportModel.java` — aggregate root; `taskId`, `attemptNo`, `verdict`, `List<CheckResult> checks`.
- `service/ValidationDomainService.java` + `service/impl/ValidationDomainServiceImpl.java` — deterministic checks; consumes `SchemaValidator`.
- `service/SchemaValidator.java` — **port** interface (domain).
- `service/JsonCheckResult.java` — VO returned by the port `(boolean validJson, boolean schemaApplicable, boolean schemaMatches, String detail)`.
- `repository/ValidationReportRepository.java` — repository interface.

**New (infrastructure):**
- `hireai-infrastructure/.../infrastructure/adjudication/NetworkntSchemaValidator.java` — `SchemaValidator` impl (networknt + Jackson).
- `hireai-repository/.../infrastructure/repository/adjudication/ValidationReportDO.java`, `ValidationReportJpaRepository.java`, `ValidationReportRepositoryImpl.java`.

**New (application):**
- `hireai-application/.../application/biz/adjudication/validation/ValidationAppService.java` + `impl/ValidationAppServiceImpl.java`.

**New (migration):**
- `hireai-main/src/main/resources/db/migration/V16__validation_reports.sql`.

**Modified:**
- `hireai-infrastructure/pom.xml` — add `json-schema-validator`; pin version in `backend/pom.xml` dependencyManagement.
- `TaskModel.java` — add `passValidation()` / `failValidation()`; move `accept()`/`reject()` guards `RESULT_RECEIVED → PENDING_REVIEW`.
- `AgentCallbackAppServiceImpl.java` — call `ValidationAppService` after `recordResult`.
- `DomainServiceConfig.java` — wire `ValidationDomainService`.
- Existing callback/accept tests that drove `RESULT_RECEIVED → accept` directly — update to go through the gate (noted in Task 7/8).

---

### Task 1: `SchemaValidator` port + networknt impl + dependency

**Files:**
- Modify: `backend/pom.xml` (dependencyManagement) and `backend/hireai-infrastructure/pom.xml` (dependency)
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/JsonCheckResult.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/SchemaValidator.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/adjudication/NetworkntSchemaValidator.java`
- Test: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/adjudication/NetworkntSchemaValidatorTest.java`

**Interfaces:**
- Produces: `SchemaValidator.check(String payloadJson, String schemaOrNull) -> JsonCheckResult`; `JsonCheckResult(boolean validJson, boolean schemaApplicable, boolean schemaMatches, String detail)`.

- [ ] **Step 1: Write the failing test**

```java
// NetworkntSchemaValidatorTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NetworkntSchemaValidatorTest {

    private final NetworkntSchemaValidator validator = new NetworkntSchemaValidator();

    @Test
    void invalidJsonIsReported() {
        JsonCheckResult r = validator.check("{not json", null);
        assertThat(r.validJson()).isFalse();
    }

    @Test
    void validJsonNoSchemaIsApplicableFalse() {
        JsonCheckResult r = validator.check("{\"a\":1}", null);
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void freeProseSchemaIsNotApplicable() {
        JsonCheckResult r = validator.check("{\"a\":1}", "must be a summary under 200 words");
        assertThat(r.validJson()).isTrue();
        assertThat(r.schemaApplicable()).isFalse();
    }

    @Test
    void jsonSchemaMatchAndMismatch() {
        String schema = "{\"type\":\"object\",\"required\":[\"title\"],\"properties\":{\"title\":{\"type\":\"string\"}}}";
        assertThat(validator.check("{\"title\":\"hi\"}", schema).schemaMatches()).isTrue();
        JsonCheckResult miss = validator.check("{\"x\":1}", schema);
        assertThat(miss.schemaApplicable()).isTrue();
        assertThat(miss.schemaMatches()).isFalse();
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=NetworkntSchemaValidatorTest`
Expected: COMPILATION FAILURE (`JsonCheckResult`/`SchemaValidator`/`NetworkntSchemaValidator` do not exist).

- [ ] **Step 3: Add the dependency**

In `backend/pom.xml`, inside `<dependencyManagement><dependencies>` (near the jjwt/testcontainers entries):

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
    <version>1.5.2</version>
</dependency>
```

In `backend/hireai-infrastructure/pom.xml`, inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
</dependency>
```

- [ ] **Step 4: Create the port + result VO (domain)**

```java
// JsonCheckResult.java
package com.hireai.domain.biz.adjudication.service;

/** Outcome of inspecting a result payload as JSON and (optionally) against a JSON Schema. */
public record JsonCheckResult(boolean validJson, boolean schemaApplicable,
                              boolean schemaMatches, String detail) {
}
```

```java
// SchemaValidator.java
package com.hireai.domain.biz.adjudication.service;

import org.jspecify.annotations.Nullable;

/**
 * Domain port for JSON / JSON-Schema inspection. Implemented in infrastructure so the domain
 * stays framework-free (no Jackson / networknt on the domain classpath).
 */
public interface SchemaValidator {

    /**
     * @param payloadJson the agent's result payload
     * @param schemaOrNull the task's declared output_spec.schema (may be null or free prose)
     */
    JsonCheckResult check(String payloadJson, @Nullable String schemaOrNull);
}
```

- [ ] **Step 5: Implement the adapter (infrastructure)**

```java
// NetworkntSchemaValidator.java
package com.hireai.infrastructure.adjudication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class NetworkntSchemaValidator implements SchemaValidator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Override
    public JsonCheckResult check(String payloadJson, String schemaOrNull) {
        JsonNode payload;
        try {
            payload = mapper.readTree(payloadJson);
        } catch (Exception e) {
            return new JsonCheckResult(false, false, false, "payload is not valid JSON: " + e.getMessage());
        }
        if (schemaOrNull == null || schemaOrNull.isBlank()) {
            return new JsonCheckResult(true, false, false, "valid JSON; no schema declared");
        }
        JsonSchema schema;
        try {
            JsonNode schemaNode = mapper.readTree(schemaOrNull);
            if (!schemaNode.isObject()) {
                return new JsonCheckResult(true, false, false, "schema is not a JSON Schema object; skipped");
            }
            schema = schemaFactory.getSchema(schemaNode);
        } catch (Exception e) {
            return new JsonCheckResult(true, false, false, "schema is free prose, not a JSON Schema; skipped");
        }
        Set<ValidationMessage> errors = schema.validate(payload);
        return new JsonCheckResult(true, true, errors.isEmpty(),
                errors.isEmpty() ? "matches schema" : errors.toString());
    }
}
```

- [ ] **Step 6: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=NetworkntSchemaValidatorTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add backend/pom.xml backend/hireai-infrastructure/pom.xml backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/ backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/adjudication/ backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/adjudication/
git commit -m "feat(adjudication): SchemaValidator port + networknt adapter"
```

---

### Task 2: `Verdict` enum + `CheckResult` VO

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/Verdict.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/CheckResult.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/CheckResultTest.java`

**Interfaces:**
- Produces: `Verdict{PASS,FAIL}`; `CheckResult(String rule, boolean passed, String detail)`.

- [ ] **Step 1: Write the failing test**

```java
// CheckResultTest.java
package com.hireai.domain.biz.adjudication.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.hireai.utility.exception.DomainException;

class CheckResultTest {
    @Test
    void holdsFields() {
        CheckResult c = new CheckResult("FORMAT_TEXT", true, "non-empty");
        assertThat(c.rule()).isEqualTo("FORMAT_TEXT");
        assertThat(c.passed()).isTrue();
        assertThat(c.detail()).isEqualTo("non-empty");
    }

    @Test
    void rejectsBlankRule() {
        assertThatThrownBy(() -> new CheckResult("  ", true, "x"))
            .isInstanceOf(DomainException.class);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=CheckResultTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create the enum + VO**

```java
// Verdict.java
package com.hireai.domain.biz.adjudication.enums;

public enum Verdict {
    PASS,
    FAIL
}
```

```java
// CheckResult.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

/** One deterministic check run by the validation gate against a result. */
public record CheckResult(String rule, boolean passed, String detail) {
    public CheckResult {
        if (rule == null || rule.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Check rule is required");
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=CheckResultTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/enums/ backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/CheckResult.java backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/CheckResultTest.java
git commit -m "feat(adjudication): Verdict enum + CheckResult VO"
```

---

### Task 3: `ValidationReportModel` aggregate

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/ValidationReportModel.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/ValidationReportModelTest.java`

**Interfaces:**
- Consumes: `CheckResult`, `Verdict`.
- Produces: `ValidationReportModel.of(UUID taskId, int attemptNo, List<CheckResult> checks)` (derives `verdict`), `rehydrate(...)`, accessors `id()`, `taskId()`, `attemptNo()`, `verdict()`, `checks()`, and `isPass()`.

- [ ] **Step 1: Write the failing test**

```java
// ValidationReportModelTest.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ValidationReportModelTest {
    private final UUID taskId = UUID.randomUUID();

    @Test
    void allPassedIsPass() {
        var r = ValidationReportModel.of(taskId, 1, List.of(
            new CheckResult("A", true, ""), new CheckResult("B", true, "")));
        assertThat(r.verdict()).isEqualTo(Verdict.PASS);
        assertThat(r.isPass()).isTrue();
        assertThat(r.id()).isNotNull();
        assertThat(r.attemptNo()).isEqualTo(1);
    }

    @Test
    void anyFailedIsFail() {
        var r = ValidationReportModel.of(taskId, 1, List.of(
            new CheckResult("A", true, ""), new CheckResult("B", false, "bad")));
        assertThat(r.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(r.isPass()).isFalse();
    }

    @Test
    void requiresAtLeastOneCheck() {
        assertThatThrownBy(() -> ValidationReportModel.of(taskId, 1, List.of()))
            .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=ValidationReportModelTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create the aggregate**

```java
// ValidationReportModel.java
package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.List;
import java.util.UUID;

/** Adjudication aggregate root: the automated validation outcome for one result attempt. */
public final class ValidationReportModel {

    private final UUID id;
    private final UUID taskId;
    private final int attemptNo;
    private final Verdict verdict;
    private final List<CheckResult> checks;

    private ValidationReportModel(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks) {
        this.id = id;
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.verdict = verdict;
        this.checks = List.copyOf(checks);
    }

    public static ValidationReportModel of(UUID taskId, int attemptNo, List<CheckResult> checks) {
        if (checks == null || checks.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A validation report needs at least one check");
        }
        Verdict verdict = checks.stream().allMatch(CheckResult::passed) ? Verdict.PASS : Verdict.FAIL;
        return new ValidationReportModel(UUID.randomUUID(), taskId, attemptNo, verdict, checks);
    }

    public static ValidationReportModel rehydrate(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks) {
        return new ValidationReportModel(id, taskId, attemptNo, verdict, checks);
    }

    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public int attemptNo() { return attemptNo; }
    public Verdict verdict() { return verdict; }
    public List<CheckResult> checks() { return checks; }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=ValidationReportModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/model/ValidationReportModel.java backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/model/ValidationReportModelTest.java
git commit -m "feat(adjudication): ValidationReport aggregate"
```

---

### Task 4: `ValidationDomainService` (the deterministic checks)

**Files:**
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/ValidationDomainService.java`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/impl/ValidationDomainServiceImpl.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/service/ValidationDomainServiceImplTest.java`

**Interfaces:**
- Consumes: `SchemaValidator`, `JsonCheckResult`, `OutputSpec`, `OutputFormat`, `TaskResultModel`, `ValidationReportModel`.
- Produces: `ValidationReportModel validate(OutputSpec spec, TaskResultModel result, int attemptNo)`.

**Decision (stated for the reviewer):** the precondition `agentStatus == "COMPLETED"` is checked by the **app service** (Task 7), which routes a non-completed agent status to `FAILED`. This domain service assumes a completed result and validates the *payload* against the spec.

- [ ] **Step 1: Write the failing test**

```java
// ValidationDomainServiceImplTest.java
package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationDomainServiceImplTest {

    // Fake SchemaValidator: parses with a trivial brace check, honours a "SCHEMA_OK"/"SCHEMA_BAD" marker.
    private final SchemaValidator fake = (payload, schema) -> {
        boolean valid = payload != null && payload.trim().startsWith("{") && payload.trim().endsWith("}");
        if (!valid) return new JsonCheckResult(false, false, false, "bad json");
        if (schema == null || schema.isBlank()) return new JsonCheckResult(true, false, false, "no schema");
        if ("SCHEMA_BAD".equals(schema)) return new JsonCheckResult(true, true, false, "mismatch");
        return new JsonCheckResult(true, true, true, "ok");
    };
    private final ValidationDomainService svc = new ValidationDomainServiceImpl(fake);
    private final UUID taskId = UUID.randomUUID();

    private TaskResultModel result(String payload, String url) {
        return TaskResultModel.record(taskId, "COMPLETED", payload, url);
    }

    @Test
    void textNonEmptyPasses() {
        var spec = new OutputSpec(OutputFormat.TEXT, null, null);
        assertThat(svc.validate(spec, result("a summary", null), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void textBlankFails() {
        var spec = new OutputSpec(OutputFormat.TEXT, null, null);
        assertThat(svc.validate(spec, result("   ", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void jsonValidNoSchemaPasses() {
        var spec = new OutputSpec(OutputFormat.JSON, null, null);
        assertThat(svc.validate(spec, result("{\"a\":1}", null), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void jsonInvalidFails() {
        var spec = new OutputSpec(OutputFormat.JSON, null, null);
        assertThat(svc.validate(spec, result("not json", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void jsonSchemaMismatchFails() {
        var spec = new OutputSpec(OutputFormat.JSON, "SCHEMA_BAD", null);
        assertThat(svc.validate(spec, result("{\"a\":1}", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void fileHttpsUrlPasses() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", "https://cdn.example.com/x.pdf"), 1).verdict()).isEqualTo(Verdict.PASS);
    }

    @Test
    void fileNonHttpsFails() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", "http://cdn.example.com/x.pdf"), 1).verdict()).isEqualTo(Verdict.FAIL);
    }

    @Test
    void fileMissingUrlFails() {
        var spec = new OutputSpec(OutputFormat.FILE, null, null);
        assertThat(svc.validate(spec, result("{}", null), 1).verdict()).isEqualTo(Verdict.FAIL);
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=ValidationDomainServiceImplTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create the interface**

```java
// ValidationDomainService.java
package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;

public interface ValidationDomainService {
    ValidationReportModel validate(OutputSpec spec, TaskResultModel result, int attemptNo);
}
```

- [ ] **Step 4: Implement the checks**

```java
// ValidationDomainServiceImpl.java
package com.hireai.domain.biz.adjudication.service.impl;

import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.JsonCheckResult;
import com.hireai.domain.biz.adjudication.service.SchemaValidator;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ValidationDomainServiceImpl implements ValidationDomainService {

    private final SchemaValidator schemaValidator;

    public ValidationDomainServiceImpl(SchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }

    @Override
    public ValidationReportModel validate(OutputSpec spec, TaskResultModel result, int attemptNo) {
        List<CheckResult> checks = new ArrayList<>();
        switch (spec.format()) {
            case TEXT -> checks.add(checkText(result.resultPayloadJson()));
            case JSON -> checks.addAll(checkJson(result.resultPayloadJson(), spec.schema()));
            case FILE -> checks.add(checkFile(result.resultUrl()));
        }
        return ValidationReportModel.of(result.taskId(), attemptNo, checks);
    }

    private CheckResult checkText(String payload) {
        boolean ok = payload != null && !payload.isBlank();
        return new CheckResult("FORMAT_TEXT_NON_EMPTY", ok, ok ? "non-empty text" : "empty/blank payload");
    }

    private List<CheckResult> checkJson(String payload, String schema) {
        JsonCheckResult r = schemaValidator.check(payload, schema);
        List<CheckResult> out = new ArrayList<>();
        out.add(new CheckResult("FORMAT_JSON_PARSEABLE", r.validJson(), r.detail()));
        if (r.validJson()) {
            if (r.schemaApplicable()) {
                out.add(new CheckResult("SCHEMA_MATCH", r.schemaMatches(), r.detail()));
            } else {
                out.add(new CheckResult("SCHEMA_SKIPPED", true, r.detail()));
            }
        }
        return out;
    }

    private CheckResult checkFile(String url) {
        boolean ok;
        String detail;
        if (url == null || url.isBlank()) {
            ok = false; detail = "no resultUrl";
        } else {
            try {
                URI uri = URI.create(url);
                ok = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
                detail = ok ? "https url" : "url is not https";
            } catch (IllegalArgumentException e) {
                ok = false; detail = "malformed url";
            }
        }
        return new CheckResult("FORMAT_FILE_HTTPS_URL", ok, detail);
    }
}
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=ValidationDomainServiceImplTest`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/service/ backend/hireai-domain/src/test/java/com/hireai/domain/biz/adjudication/service/
git commit -m "feat(adjudication): deterministic ValidationDomainService"
```

---

### Task 5: `validation_reports` migration + persistence

**Files:**
- Create: `backend/hireai-main/src/main/resources/db/migration/V16__validation_reports.sql`
- Create: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/ValidationReportRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ValidationReportDO.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ValidationReportJpaRepository.java`
- Create: `backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ValidationReportRepositoryImpl.java`
- Test: `backend/hireai-main/src/test/java/com/hireai/adjudication/ValidationReportRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `ValidationReportModel`, `CheckResult`, `Verdict`.
- Produces: `ValidationReportRepository.save(ValidationReportModel) -> ValidationReportModel`; `findByTaskIdAndAttemptNo(UUID, int) -> Optional<ValidationReportModel>`.

- [ ] **Step 1: Write the failing test** (Testcontainers; auto-skips without Docker)

```java
// ValidationReportRepositoryIntegrationTest.java
package com.hireai.adjudication;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.support.AbstractPostgresIntegrationTest; // existing base; mirror another *IntegrationTest's setup
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationReportRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired ValidationReportRepository repo;

    @Test
    void roundTrips() {
        UUID taskId = UUID.randomUUID();
        var saved = repo.save(ValidationReportModel.of(taskId, 1, List.of(
            new CheckResult("FORMAT_JSON_PARSEABLE", true, "ok"),
            new CheckResult("SCHEMA_SKIPPED", true, "no schema"))));
        var found = repo.findByTaskIdAndAttemptNo(taskId, 1).orElseThrow();
        assertThat(found.verdict()).isEqualTo(Verdict.PASS);
        assertThat(found.checks()).hasSize(2);
        assertThat(found.checks().get(0).rule()).isEqualTo("FORMAT_JSON_PARSEABLE");
        assertThat(found.id()).isEqualTo(saved.id());
    }
}
```

> **Implementer note:** match the exact base-class/annotations used by an existing `*IntegrationTest` in `hireai-main` (e.g. the settlement or wallet-version integration test) for the Testcontainers Postgres setup — `AbstractPostgresIntegrationTest` is a placeholder name for whatever that project base is.

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ValidationReportRepositoryIntegrationTest`
Expected: COMPILATION FAILURE (repo + types missing). (If no Docker, it would later skip — but it must compile.)

- [ ] **Step 3: Create the migration**

```sql
-- V16__validation_reports.sql
CREATE TABLE validation_reports (
    id           UUID PRIMARY KEY,
    task_id      UUID NOT NULL,
    attempt_no   INT  NOT NULL DEFAULT 1,
    verdict      TEXT NOT NULL CHECK (verdict IN ('PASS', 'FAIL')),
    checks       JSONB NOT NULL,
    gmt_create   TIMESTAMPTZ NOT NULL DEFAULT now(),
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (task_id, attempt_no)
);
```

- [ ] **Step 4: Create the domain repository interface**

```java
// ValidationReportRepository.java
package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import java.util.Optional;
import java.util.UUID;

public interface ValidationReportRepository {
    ValidationReportModel save(ValidationReportModel report);
    Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);
}
```

- [ ] **Step 5: Create the DO** (mirror `TaskResultDO`'s `@JdbcTypeCode(SqlTypes.JSON)` for the `checks` column)

```java
// ValidationReportDO.java
package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity
@Table(name = "validation_reports")
public class ValidationReportDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "verdict", nullable = false)
    private String verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", columnDefinition = "jsonb", nullable = false)
    private String checks; // JSON array string of {rule,passed,detail}

    protected ValidationReportDO() {
    }

    public ValidationReportDO(UUID id, UUID taskId, int attemptNo, String verdict, String checks) {
        this.id = id;
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.verdict = verdict;
        this.checks = checks;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public int getAttemptNo() { return attemptNo; }
    public String getVerdict() { return verdict; }
    public String getChecks() { return checks; }
}
```

- [ ] **Step 6: Create the Spring-Data repo**

```java
// ValidationReportJpaRepository.java
package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ValidationReportJpaRepository extends JpaRepository<ValidationReportDO, UUID> {
    Optional<ValidationReportDO> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);
}
```

- [ ] **Step 7: Implement the domain repository** (Jackson lives in infra; serialize `checks` to JSON)

```java
// ValidationReportRepositoryImpl.java
package com.hireai.infrastructure.repository.adjudication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ValidationReportRepositoryImpl implements ValidationReportRepository {

    private final ValidationReportJpaRepository jpa;
    private final ObjectMapper mapper = new ObjectMapper();

    public ValidationReportRepositoryImpl(ValidationReportJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ValidationReportModel save(ValidationReportModel r) {
        jpa.save(new ValidationReportDO(r.id(), r.taskId(), r.attemptNo(), r.verdict().name(), writeChecks(r.checks())));
        return r;
    }

    @Override
    public Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo) {
        return jpa.findByTaskIdAndAttemptNo(taskId, attemptNo).map(this::toModel);
    }

    private ValidationReportModel toModel(ValidationReportDO d) {
        return ValidationReportModel.rehydrate(d.getId(), d.getTaskId(), d.getAttemptNo(),
            Verdict.valueOf(d.getVerdict()), readChecks(d.getChecks()));
    }

    private String writeChecks(List<CheckResult> checks) {
        try {
            return mapper.writeValueAsString(checks);
        } catch (Exception e) {
            throw new IllegalStateException("serialize checks", e);
        }
    }

    private List<CheckResult> readChecks(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<CheckResult>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("deserialize checks", e);
        }
    }
}
```

- [ ] **Step 8: Run the test, verify it passes** (with Docker running)

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ValidationReportRepositoryIntegrationTest`
Expected: PASS (or SKIPPED if no Docker — confirm it at least compiles + the module builds).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-main/src/main/resources/db/migration/V16__validation_reports.sql backend/hireai-domain/src/main/java/com/hireai/domain/biz/adjudication/repository/ backend/hireai-repository/src/main/java/com/hireai/infrastructure/repository/adjudication/ backend/hireai-main/src/test/java/com/hireai/adjudication/
git commit -m "feat(adjudication): validation_reports table + persistence (V16)"
```

---

### Task 6: Task transitions — `passValidation()` / `failValidation()` + move review guards

**Files:**
- Modify: `backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java`
- Test: `backend/hireai-domain/src/test/java/com/hireai/domain/biz/task/model/TaskModelValidationTransitionsTest.java`

**Interfaces:**
- Produces: `TaskModel.passValidation()` (`RESULT_RECEIVED → PENDING_REVIEW`), `TaskModel.failValidation()` (`RESULT_RECEIVED → SPEC_VIOLATION`). `accept()`/`reject()` now require `PENDING_REVIEW`.

- [ ] **Step 1: Write the failing test**

```java
// TaskModelValidationTransitionsTest.java
package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.task.enums.TaskStatus;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskModelValidationTransitionsTest {

    // Helper: build a task in RESULT_RECEIVED. Mirror how other TaskModel tests construct a task
    // (submit() → assignAndQueue() → markExecuting() → recordResult(...)).
    private TaskModel resultReceivedTask() {
        // Implementer: reuse the existing TaskModel test fixture/builder used elsewhere.
        throw new UnsupportedOperationException("use existing TaskModel test fixture");
    }

    @Test
    void passValidationGoesToPendingReview() {
        TaskModel t = resultReceivedTask().passValidation();
        assertThat(t.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
    }

    @Test
    void failValidationGoesToSpecViolation() {
        TaskModel t = resultReceivedTask().failValidation();
        assertThat(t.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
    }

    @Test
    void acceptRequiresPendingReview() {
        assertThatThrownBy(() -> resultReceivedTask().accept())
            .isInstanceOf(RuntimeException.class);
        assertThat(resultReceivedTask().passValidation().accept().status()).isEqualTo(TaskStatus.RESOLVED);
    }

    @Test
    void rejectRequiresPendingReview() {
        assertThatThrownBy(() -> resultReceivedTask().reject("x"))
            .isInstanceOf(RuntimeException.class);
    }
}
```

> **Implementer note:** replace `resultReceivedTask()` with the real fixture pattern used by the existing `TaskModel` unit tests (look at the test that exercises `recordResult`/`accept`).

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=TaskModelValidationTransitionsTest`
Expected: FAIL (`passValidation`/`failValidation` missing; `accept` still allows `RESULT_RECEIVED`).

- [ ] **Step 3: Add the transitions and move the guards**

In `TaskModel.java`, add two methods next to `recordResult` (mirror its `requireStatus` + `copyWith` style):

```java
public TaskModel passValidation() {
    requireStatus(TaskStatus.RESULT_RECEIVED);
    return copyWith(TaskStatus.PENDING_REVIEW);
}

public TaskModel failValidation() {
    requireStatus(TaskStatus.RESULT_RECEIVED);
    return copyWith(TaskStatus.SPEC_VIOLATION);
}
```

Then change the guards in `accept()` and `reject(...)` from `requireStatus(TaskStatus.RESULT_RECEIVED)` to `requireStatus(TaskStatus.PENDING_REVIEW)`.

> Use the exact `copyWith`/resolved-helper signatures already in the file (the explorer confirmed `copyWith()` at line 161 and a private resolved helper at line 155). For `accept()`/`reject()` keep the existing resolution-setting logic; only the status guard changes.

- [ ] **Step 4: Run the test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-domain -am test -Dtest=TaskModelValidationTransitionsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-domain/src/main/java/com/hireai/domain/biz/task/model/TaskModel.java backend/hireai-domain/src/test/java/com/hireai/domain/biz/task/model/TaskModelValidationTransitionsTest.java
git commit -m "feat(task): validation transitions + review guards on PENDING_REVIEW"
```

---

### Task 7: `ValidationAppService` + wire into the callback

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/validation/ValidationAppService.java`
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/validation/impl/ValidationAppServiceImpl.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/config/DomainServiceConfig.java`
- Modify: `backend/hireai-application/src/main/java/com/hireai/application/biz/agentcallback/impl/AgentCallbackAppServiceImpl.java`
- Test: `backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/ValidationAppServiceImplTest.java`

**Interfaces:**
- Consumes: `ValidationDomainService`, `ValidationReportRepository`, `TaskRepository` (existing), `SettlementWriteAppService` (existing — `settleRejected(taskId, clientId, budget)`), `TaskModel`.
- Produces: `ValidationAppService.validateAndGate(TaskModel task)` — persists the report, transitions the task (`passValidation`/`failValidation`), saves it, and on FAIL calls `settleRejected(...)` (full refund). Returns the gated `TaskModel`.

- [ ] **Step 1: Write the failing test** (Mockito; pure unit)

```java
// ValidationAppServiceImplTest.java
package com.hireai.application.biz.adjudication;

import com.hireai.application.biz.adjudication.validation.impl.ValidationAppServiceImpl;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class ValidationAppServiceImplTest {

    private final ValidationDomainService domain = mock(ValidationDomainService.class);
    private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final SettlementWriteAppService settlement = mock(SettlementWriteAppService.class);
    private final ValidationAppServiceImpl svc = new ValidationAppServiceImpl(domain, reports, tasks, settlement);

    // Implementer: build a RESULT_RECEIVED TaskModel with a known clientId + budget via the existing fixture.
    private TaskModel resultReceivedTask() { throw new UnsupportedOperationException("use fixture"); }

    @Test
    void passMovesToPendingReviewNoRefund() {
        TaskModel task = resultReceivedTask();
        when(domain.validate(any(), any(), eq(1)))
            .thenReturn(ValidationReportModel.of(task.id(), 1, List.of(new CheckResult("A", true, ""))));
        TaskModel gated = svc.validateAndGate(task);
        assertThat(gated.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
        verify(reports).save(any());
        verify(settlement, never()).settleRejected(any(), any(), any());
    }

    @Test
    void failMovesToSpecViolationAndRefunds() {
        TaskModel task = resultReceivedTask();
        when(domain.validate(any(), any(), eq(1)))
            .thenReturn(ValidationReportModel.of(task.id(), 1, List.of(new CheckResult("A", false, "bad"))));
        TaskModel gated = svc.validateAndGate(task);
        assertThat(gated.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
        verify(settlement).settleRejected(eq(task.id()), eq(task.clientId()), eq(task.budget()));
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=ValidationAppServiceImplTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create the interface**

```java
// ValidationAppService.java
package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.lang.NonNull;

public interface ValidationAppService {
    TaskModel validateAndGate(@NonNull TaskModel task);
}
```

- [ ] **Step 4: Implement it** (runs the gate; the task is already loaded + `RESULT_RECEIVED` from the callback)

```java
// ValidationAppServiceImpl.java
package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationAppServiceImpl implements ValidationAppService {

    private final ValidationDomainService validationDomainService;
    private final ValidationReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;

    private static final int FIRST_ATTEMPT = 1; // retry attempts arrive in Plan 2

    @Override
    public TaskModel validateAndGate(TaskModel task) {
        ValidationReportModel report =
            validationDomainService.validate(task.outputSpec(), task.result(), FIRST_ATTEMPT);
        reportRepository.save(report);

        if (report.isPass()) {
            TaskModel gated = task.passValidation();
            taskRepository.save(gated);
            log.info("Task {} passed validation -> PENDING_REVIEW", task.id());
            return gated;
        }
        TaskModel gated = task.failValidation();
        taskRepository.save(gated);
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        log.info("Task {} failed validation -> SPEC_VIOLATION (refunded)", task.id());
        return gated;
    }
}
```

> **Implementer note:** confirm the exact accessors on `TaskModel` for the frozen spec and the recorded result (the explorer showed `recordResult(TaskResultModel)` and accessors at lines 184–197). If the getters are named differently than `outputSpec()` / `result()`, use the real names. If `TaskModel` does not expose the `TaskResultModel`, pass the result the callback just built into `validateAndGate(task, result)` instead — adjust the signature consistently across this task and Task 8.

- [ ] **Step 5: Wire the domain service bean**

In `DomainServiceConfig.java`, add (Spring injects the `SchemaValidator` infra bean):

```java
@Bean
public ValidationDomainService validationDomainService(
        com.hireai.domain.biz.adjudication.service.SchemaValidator schemaValidator) {
    return new com.hireai.domain.biz.adjudication.service.impl.ValidationDomainServiceImpl(schemaValidator);
}
```

- [ ] **Step 6: Call the gate from the callback**

In `AgentCallbackAppServiceImpl.recordResult(...)`, after the line that saves the recorded result, add the gate. Inject `ValidationAppService` and the precondition:

```java
// after: TaskModel recorded = taskRepository.save(task.recordResult(resultModel));
if (!"COMPLETED".equalsIgnoreCase(result.agentStatus())) {
    TaskModel failed = recorded.markFailed();
    taskRepository.save(failed);
    settlementWriteAppService.settleRejected(failed.id(), failed.clientId(), failed.budget());
    log.info("Task {} agent reported {} -> FAILED (refunded)", taskId, result.agentStatus());
    return;
}
validationAppService.validateAndGate(recorded);
```

> Add `ValidationAppService validationAppService` and `SettlementWriteAppService settlementWriteAppService` to the constructor (the class is `@RequiredArgsConstructor` per the explorer — add the fields). Ensure `task.recordResult(...)`'s return is captured as `recorded` (a `TaskModel` carrying the result) so the gate can read it; if `save` returns void, re-load or use the in-memory `task.recordResult(resultModel)` value.

- [ ] **Step 7: Run the unit test, verify it passes**

Run: `mvn -f backend/pom.xml -pl hireai-application -am test -Dtest=ValidationAppServiceImplTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/ backend/hireai-application/src/main/java/com/hireai/application/config/DomainServiceConfig.java backend/hireai-application/src/main/java/com/hireai/application/biz/agentcallback/impl/AgentCallbackAppServiceImpl.java backend/hireai-application/src/test/java/com/hireai/application/biz/adjudication/
git commit -m "feat(adjudication): validation gate in the callback (+auto-refund on violation)"
```

---

### Task 8: End-to-end integration test + fix affected existing tests

**Files:**
- Create: `backend/hireai-main/src/test/java/com/hireai/adjudication/ValidationGateIntegrationTest.java`
- Modify: any existing callback/accept integration tests that asserted `RESULT_RECEIVED → accept` directly.

**Interfaces:**
- Consumes: the whole assembled context (callback → gate → settlement).

- [ ] **Step 1: Write the failing test** (Testcontainers; auto-skips without Docker)

```java
// ValidationGateIntegrationTest.java — outline; flesh out using the existing dispatch/callback integration test as the template.
// Scenario A (PASS): submit a task (JSON spec, no schema), drive it to EXECUTING, POST a valid-JSON result
//   to the callback with a valid dispatch token -> assert task status == PENDING_REVIEW and a
//   validation_reports row exists with verdict PASS.
// Scenario B (FAIL): same, but POST an invalid-JSON payload -> assert status == SPEC_VIOLATION,
//   verdict FAIL, and the client's wallet escrow was refunded (available restored, escrow released).
```

> **Implementer note:** copy the setup (Testcontainers Postgres + RabbitMQ, token minting, task fixture) from the existing dispatch round-trip / callback integration test. Assert wallet balances via the existing wallet repository/read service used by other settlement integration tests.

- [ ] **Step 2: Run it, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ValidationGateIntegrationTest`
Expected: FAIL/SKIP appropriately; must compile.

- [ ] **Step 3: Make it pass** — only wiring from prior tasks should be needed. Fix any compile/logic gaps.

- [ ] **Step 4: Update affected existing tests**

Run the full suite and update any test that previously drove `RESULT_RECEIVED → accept/reject` directly, since accept/reject now require `PENDING_REVIEW`. Such tests should either (a) route through the callback gate, or (b) call `passValidation()` before `accept()`.

Run: `mvn -f backend/pom.xml -B test`
Expected: BUILD SUCCESS, 0 failures/errors (integration tests SKIPPED only if Docker is down).

- [ ] **Step 5: Commit**

```bash
git add backend/hireai-main/src/test/java/com/hireai/adjudication/ValidationGateIntegrationTest.java
git commit -m "test(adjudication): end-to-end validation gate"
```

---

## Self-review (against the spec)

**Spec coverage:** validation gate (deterministic structural, format + JSON-Schema-when-present, `acceptanceCriteria` not machine-judged) → Tasks 1,4; `RESULT_RECEIVED → PENDING_REVIEW/SPEC_VIOLATION` → Task 6; runs sync in the callback tx → Task 7; auto-refund on `SPEC_VIOLATION` → Task 7; accept/reject move to `PENDING_REVIEW` → Task 6; `validation_reports` schema (V16, per-attempt) → Task 5; framework-free domain via `SchemaValidator` port → Task 1; agentStatus≠COMPLETED → FAILED+refund → Task 7. **Deferred to Plan 2 (explicitly):** bounded retry, auto-accept sweeper, `TIMED_OUT`/`FAILED` refund wiring (no production callers yet), `tasks.validation_attempts`/`review_deadline`/`task_results.attempt_no` plumbing. **Gap noted:** the spec folds `TIMED_OUT`/`FAILED` auto-refund into Phase 1; since those transitions have no production callers today, their refund wiring lands with the reliability work that introduces the callers — recorded here so it isn't lost.

**Placeholder scan:** the two `resultReceivedTask()` fixtures and `AbstractPostgresIntegrationTest` are intentional pointers to existing project fixtures (named, with implementer notes), not hidden TODOs.

**Type consistency:** `validate(OutputSpec, TaskResultModel, int)`, `validateAndGate(TaskModel)`, `settleRejected(UUID,UUID,Money)`, `findByTaskIdAndAttemptNo(UUID,int)`, `Verdict{PASS,FAIL}`, `CheckResult(rule,passed,detail)` are used consistently across tasks. The one open item flagged for the implementer: confirm `TaskModel`'s accessors for the frozen `OutputSpec` and recorded `TaskResultModel`; if absent, thread the result through `validateAndGate(task, result)` (adjust Tasks 7 & 8 together).
