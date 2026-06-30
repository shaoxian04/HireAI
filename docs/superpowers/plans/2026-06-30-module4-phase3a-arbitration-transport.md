# Module 4 Phase 3a — Async Arbitration Transport (RabbitMQ) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the synchronous `StubArbitrationClient` (in production) with a real **async** arbitration transport: a `RabbitArbitrationClient` publishes a dispute to a durable `task.dispute.requested` queue (moving the dispute to `ARBITRATING`), an external worker posts the ruling back to a shared-secret `ArbitrationCallbackController`, and an `ArbitrationDlqListener` guarantees termination — a poison/exhausted request dead-letters to a full refund (兜底). The actual ruling→money settlement is unchanged from Phase 2 (it already runs in `DisputeAppService`).

**Architecture:** The Phase-2 `ArbitrationGateway` port already returns `Optional<RulingInfo>` — *present* = sync (the stub, kept for tests), *empty* = async (this phase). The Rabbit adapter returns `empty`, so `DisputeAppService.openDispute` already saves `dispute.startArbitrating()`. The ruling lands later via `POST /api/arbitration-callbacks/{disputeId}/ruling` → `DisputeAppService.applyRuling` (first-ruling-wins). Adapter selection is **profile-based**: stub in `test`, Rabbit in `!test`. The arbitration queue mirrors the existing `task.dispatch` exchange/queue/DLQ topology. The Python worker is **out of scope** (Phase 3b); this phase tests the transport by simulating the worker (posting to the callback) and by directly dead-lettering to exercise the fallback.

**Tech Stack:** Java 21, Spring Boot 3.3.5 + Spring AMQP, COLA multi-module reactor, PostgreSQL + Flyway, RabbitMQ (Jackson2JsonMessageConverter), JUnit 5 + AssertJ + Mockito + Testcontainers (Postgres + RabbitMQ).

## Global Constraints

- **Inv #6 — signed/secret Agent & service I/O.** The ruling callback is a THIRD auth system (distinct from the user JWT and the agent dispatch token): a shared secret. Compare it in constant time; HTTPS-only is enforced at deploy. Bad/missing secret → `401`.
- **Inv #3 — deterministic money, unchanged.** This phase moves NO money math. The callback hands `DisputeAppService.applyRuling` only a `{category, rationale}` `RulingInfo`; settlement is computed in `ledger.settlement` exactly as Phase 2. The DLQ fallback calls the existing `DisputeAppService.resolveByFallback` (full refund).
- **First-ruling-wins.** `applyRuling` already no-ops when the dispute is not `isResolvable()` (OPEN/ARBITRATING) — a duplicate/late callback returns `200` no-op. Do not re-implement idempotency in the controller.
- **Profile-based adapter selection.** `RabbitArbitrationClient` is `@Profile("!test")` (prod + the transport IT); `StubArbitrationClient` becomes `@Profile("test")` (replacing its `@ConditionalOnMissingBean`). Exactly one `ArbitrationGateway` bean per profile — deterministic, no component-scan ordering. **Every existing Phase-2 `@ActiveProfiles("test")` test keeps the synchronous stub and must stay green untouched.**
- **Mirror the existing dispatch transport.** The arbitration exchange/queue/DLQ/bindings and the JSON converter mirror `RabbitDispatchConfig`/`DispatchQueues`/`RabbitTaskDispatchPublisher`. The callback controller mirrors `AgentCallbackController` (manual auth + `@ExceptionHandler`, `permitAll` route). READ those real files and follow them — do not invent new patterns.
- **No new migration.** Phase 3a is transport only; the `disputes` schema (V17) + ruling columns already exist. Latest migration stays **V18**.
- **Test placement.** Unit tests per module (domain/app/infra/controller). The transport integration test goes in `hireai-main/src/test/...` and uses Testcontainers Postgres **+ RabbitMQ**, auto-skipping without Docker (never failing the build).

**Out of scope (→ Phase 3b / later):** the Python `arbitration/` FastAPI + LangGraph + Claude worker; the contract test against a live Python service; SSE push; Plan 2 (retry + auto-accept sweeper). This phase's transport is proven by simulating the worker over the callback + directly exercising the DLQ listener.

---

## File structure

**New (application):**
- `application/biz/adjudication/port/ArbitrationRequestMessage.java` — the publish payload record `(disputeId, taskId, correlationId, format, schema, acceptanceCriteria, resultPayloadJson, resultUrl, reasonCategory)`.

**New (infrastructure):**
- `infrastructure/messaging/ArbitrationQueues.java` — queue/exchange/routing-key/DLQ/DLX string constants.
- `infrastructure/messaging/RabbitArbitrationConfig.java` — exchange + queue (dead-lettered) + DLQ + bindings (mirrors `RabbitDispatchConfig`).
- `infrastructure/adjudication/RabbitArbitrationClient.java` — `@Profile("!test")` `ArbitrationGateway` impl; publishes + returns `Optional.empty()`.
- `infrastructure/messaging/ArbitrationDlqListener.java` — `@RabbitListener` on the arbitration DLQ → `resolveByFallback`.

**New (controller):**
- `controller/biz/adjudication/ArbitrationCallbackController.java` — `POST /api/arbitration-callbacks/{disputeId}/ruling`.
- `controller/biz/adjudication/dto/ArbitrationRulingRequest.java` — strict body `(String category, String rationale)`.

**New (migration):** none.

**Modified:**
- `infrastructure/adjudication/StubArbitrationClient.java` — swap `@ConditionalOnMissingBean(name="rabbitArbitrationClient")` → `@Profile("test")`.
- `controller/.../config/SecurityConfig.java` — add `/api/arbitration-callbacks/**` to the `permitAll` block in `securedFilterChain`.
- `hireai-main/src/main/resources/application.yml` — add `hireai.arbitration.callback-secret` (env `ARBITRATION_CALLBACK_SECRET`).

---

### Task 1: Arbitration Rabbit config + request message + publisher (profile-gated)

The outbound half: the queue topology, the message, and the `RabbitArbitrationClient` that publishes it and returns `empty` (async). Flip the stub to `@Profile("test")`.

**Files:**
- Create: `backend/hireai-application/src/main/java/com/hireai/application/biz/adjudication/port/ArbitrationRequestMessage.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationQueues.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/RabbitArbitrationConfig.java`
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/adjudication/RabbitArbitrationClient.java`
- Modify: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/adjudication/StubArbitrationClient.java`
- Test: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/adjudication/RabbitArbitrationClientTest.java`

**Interfaces:**
- Produces: `ArbitrationRequestMessage(UUID disputeId, UUID taskId, String correlationId, String format, String schema, String acceptanceCriteria, String resultPayloadJson, String resultUrl, String reasonCategory)`; `ArbitrationQueues.{EXCHANGE,QUEUE,ROUTING_KEY,DLQ,DLX}`; `RabbitArbitrationClient implements ArbitrationGateway` returning `Optional.empty()`.

> IMPLEMENTER NOTE — READ `RabbitDispatchConfig.java`, `DispatchQueues.java`, and `RabbitTaskDispatchPublisher.java` first and MIRROR them. Reuse the existing JSON `MessageConverter`/`RabbitTemplate` wiring approach (you may inject the existing `@Qualifier("dispatchRabbitTemplate") RabbitTemplate` — its converter is `Jackson2JsonMessageConverter` — and call the 3-arg `convertAndSend(EXCHANGE, ROUTING_KEY, message)` with the ARBITRATION constants; or declare a dedicated `arbitrationRabbitTemplate` mirroring `dispatchRabbitTemplate`. Pick whichever matches the project's style; document the choice).

- [ ] **Step 1: Write the failing test** (publisher publishes the right message + returns empty; mock `RabbitTemplate`)

```java
// RabbitArbitrationClientTest.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.model.Money;
import com.hireai.infrastructure.messaging.ArbitrationQueues;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RabbitArbitrationClientTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitArbitrationClient client = new RabbitArbitrationClient(rabbitTemplate);

    @Test
    void publishesRequestAndReturnsEmptyForAsync() {
        UUID clientId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "be correct"), "cat");
        TaskModel task = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "{\"a\":1}", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "wrong");
        DisputeModel dispute = DisputeModel.open(task.id(), clientId, RejectReason.A_MISMATCH, "dispute-" + task.id());

        Optional<RulingInfo> result = client.requestRuling(dispute, task);

        assertThat(result).isEmpty(); // async — ruling arrives via callback
        ArgumentCaptor<ArbitrationRequestMessage> cap = ArgumentCaptor.forClass(ArbitrationRequestMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(ArbitrationQueues.EXCHANGE), eq(ArbitrationQueues.ROUTING_KEY), cap.capture());
        ArbitrationRequestMessage msg = cap.getValue();
        assertThat(msg.disputeId()).isEqualTo(dispute.id());
        assertThat(msg.taskId()).isEqualTo(task.id());
        assertThat(msg.correlationId()).isEqualTo(dispute.correlationId());
        assertThat(msg.format()).isEqualTo("JSON");
        assertThat(msg.acceptanceCriteria()).isEqualTo("be correct"); // arbitrator sees the subjective criteria
        assertThat(msg.resultPayloadJson()).isEqualTo("{\"a\":1}");
        assertThat(msg.reasonCategory()).isEqualTo("A_MISMATCH");
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=RabbitArbitrationClientTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Create `ArbitrationRequestMessage`**

```java
// ArbitrationRequestMessage.java
package com.hireai.application.biz.adjudication.port;

import java.util.UUID;

/**
 * The arbitration request published to the worker (Java → queue → Python). Carries the FULL output
 * spec — including acceptanceCriteria, the subjective judgement the validation gate deliberately
 * skipped — plus the agent's result. No money/identity fields (Inv #3): the worker returns only a ruling.
 */
public record ArbitrationRequestMessage(
        UUID disputeId, UUID taskId, String correlationId,
        String format, String schema, String acceptanceCriteria,
        String resultPayloadJson, String resultUrl, String reasonCategory) {}
```

- [ ] **Step 4: Create `ArbitrationQueues`** (mirror `DispatchQueues` naming)

```java
// ArbitrationQueues.java
package com.hireai.infrastructure.messaging;

public final class ArbitrationQueues {
    public static final String EXCHANGE    = "task.dispute.exchange";
    public static final String QUEUE       = "task.dispute.requested";
    public static final String ROUTING_KEY = "task.dispute.requested";
    public static final String DLX         = "task.dispute.dlx";
    public static final String DLQ         = "task.dispute.requested.dlq";

    private ArbitrationQueues() {}
}
```

- [ ] **Step 5: Create `RabbitArbitrationConfig`** (mirror `RabbitDispatchConfig` exactly: a `DirectExchange` (durable), the main `Queue` dead-lettered to the DLX/DLQ, the DLX `DirectExchange`, the DLQ `Queue`, and the two `Binding`s). Reuse the existing JSON `MessageConverter` bean. READ `RabbitDispatchConfig.java` and copy its structure, substituting the `ArbitrationQueues` constants. (Do not redeclare a second `MessageConverter`/`ConnectionFactory` if the dispatch config already provides reusable ones — follow the project's wiring.)

- [ ] **Step 6: Create `RabbitArbitrationClient`** (`@Profile("!test")`, bean name `rabbitArbitrationClient`)

```java
// RabbitArbitrationClient.java
package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.infrastructure.messaging.ArbitrationQueues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Async arbitration adapter (production). Publishes the dispute to the worker queue and returns empty —
 * {@link com.hireai.application.biz.adjudication.dispute.DisputeAppService#openDispute} then moves the
 * dispute to ARBITRATING; the ruling arrives later via the arbitration ruling callback. Active in every
 * profile except {@code test} (where the synchronous {@code StubArbitrationClient} runs instead).
 */
@Slf4j
@Component("rabbitArbitrationClient")
@Profile("!test")
public class RabbitArbitrationClient implements ArbitrationGateway {

    private final RabbitTemplate rabbitTemplate;

    public RabbitArbitrationClient(@Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task) {
        OutputSpec spec = task.outputSpec();
        TaskResultModel result = task.result();
        ArbitrationRequestMessage message = new ArbitrationRequestMessage(
                dispute.id(), task.id(), dispute.correlationId(),
                spec.format().name(), spec.schema(), spec.acceptanceCriteria(),
                result == null ? null : result.resultPayloadJson(),
                result == null ? null : result.resultUrl(),
                dispute.reasonCategory().name());
        rabbitTemplate.convertAndSend(ArbitrationQueues.EXCHANGE, ArbitrationQueues.ROUTING_KEY, message);
        log.info("Published arbitration request for dispute {} (correlation {})", dispute.id(), dispute.correlationId());
        return Optional.empty();
    }
}
```

> IMPLEMENTER NOTE — confirm the `dispatchRabbitTemplate` bean name + that its converter is JSON (read `RabbitDispatchConfig`). If the project prefers a dedicated template, declare `arbitrationRabbitTemplate` in `RabbitArbitrationConfig` and qualify that instead; keep it consistent and note the choice.

- [ ] **Step 7: Flip the stub to `@Profile("test")`**

In `StubArbitrationClient.java`: replace `@ConditionalOnMissingBean(name = "rabbitArbitrationClient")` (and its import) with `@org.springframework.context.annotation.Profile("test")`. Refresh the class Javadoc: "Active in the `test` profile only; production uses `RabbitArbitrationClient` (async)." Keep the deterministic reason→ruling mapping unchanged.

- [ ] **Step 8: Run the test, verify it passes; then the full build**

Run: `mvn -f backend/pom.xml -pl hireai-infrastructure -am test -Dtest=RabbitArbitrationClientTest`
Then: `mvn -f backend/pom.xml -q -B test`
Expected: PASS, then BUILD SUCCESS — **all Phase-2 `@ActiveProfiles("test")` integration tests still green on the stub** (verify no dispute IT regressed; the stub is still the test-profile bean).

- [ ] **Step 9: Commit**

```bash
git add backend/hireai-application backend/hireai-infrastructure
git commit -m "feat(adjudication): async arbitration publisher + queue topology (profile-gated)"
```

---

### Task 2: Ruling callback controller + shared-secret auth + security permitAll

The inbound half: the worker posts `{category, rationale}`; we authenticate the shared secret, map to `RulingInfo`, and call `DisputeAppService.applyRuling` (first-ruling-wins lives there).

**Files:**
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/dto/ArbitrationRulingRequest.java`
- Create: `backend/hireai-controller/src/main/java/com/hireai/controller/biz/adjudication/ArbitrationCallbackController.java`
- Modify: `backend/hireai-controller/src/main/java/com/hireai/controller/.../config/SecurityConfig.java`
- Modify: `backend/hireai-main/src/main/resources/application.yml`
- Test: `backend/hireai-main/src/test/java/com/hireai/controller/biz/adjudication/ArbitrationCallbackControllerTest.java`

**Interfaces:**
- Consumes: `DisputeAppService.applyRuling(UUID disputeId, RulingInfo ruling)`; `RulingInfo(RulingCategory, String)`; `RulingCategory.valueOf`.
- Produces: `POST /api/arbitration-callbacks/{disputeId}/ruling`, body `ArbitrationRulingRequest(@NotBlank String category, String rationale)`, header `Authorization: Bearer <secret>`.

> DESIGN NOTES:
> - Auth: read the configured secret from `${hireai.arbitration.callback-secret}`; extract the bearer token from the `Authorization` header; compare with `java.security.MessageDigest.isEqual(...)` (constant-time, on UTF-8 bytes). Missing/blank/mismatch → throw a dedicated `ArbitrationAuthException` (a `RuntimeException` in the controller package) handled by an `@ExceptionHandler` → `401` (mirror `AgentCallbackController`'s `DispatchTokenInvalidException` → 401 pattern).
> - Body→domain: `RulingCategory.valueOf(request.category())`; a value not in the enum throws `IllegalArgumentException` → handle as `400` (malformed ruling = arbitrator failure, Inv #3). `@Valid` `@NotBlank category` also yields `400`.
> - `applyRuling` throws `DomainException(NOT_FOUND)` for an unknown `disputeId` → ensure the existing global handler maps it to `404` (check `GlobalExceptionConfiguration`); a not-`ARBITRATING` dispute → `applyRuling` no-ops → return `200` (first-ruling-wins). Success → `200` (return `WebResult.ok(null)` or the project's empty success envelope, mirroring `AgentCallbackController`).

- [ ] **Step 1: Write the failing controller test** (MockMvc; mock `DisputeAppService`)

```java
// ArbitrationCallbackControllerTest.java  (in hireai-main test, @WebMvcTest or @SpringBootTest + MockMvc per project convention — mirror TaskControllerTest / AgentCallbackController test setup)
// Cases:
//  - valid secret + valid body {category:"FULFILLED", rationale:"ok"} → 200, verify disputeAppService.applyRuling(disputeId, RulingInfo(FULFILLED,"ok"))
//  - missing/blank Authorization → 401, applyRuling never called
//  - wrong secret → 401
//  - malformed category {category:"BOGUS"} with valid secret → 400, applyRuling never called
//  - blank category → 400
//  - applyRuling throws DomainException(NOT_FOUND) → 404
```

> IMPLEMENTER NOTE — match the EXACT controller-test style used by the existing `AgentCallbackController` test (or `TaskControllerTest`): how MockMvc + the security/`CurrentUserProvider` mocks are set up, and how the configured `hireai.arbitration.callback-secret` is provided to the test context (e.g. `@TestPropertySource(properties = "hireai.arbitration.callback-secret=test-secret")` or the `test` profile yaml). Write the failing test FIRST and watch it fail to compile.

- [ ] **Step 2 – 5: Implement** the DTO, the controller (auth + map + delegate + `@ExceptionHandler` for the auth exception), add `"/api/arbitration-callbacks/**"` to the `permitAll()` block in `securedFilterChain` (READ `SecurityConfig.java` and add it beside `"/api/agent-callbacks/**"`), and add to `application.yml`:

```yaml
hireai:
  arbitration:
    callback-secret: ${ARBITRATION_CALLBACK_SECRET:dev-only-arbitration-callback-secret-change-me}
```

(Full controller code is straightforward; mirror `AgentCallbackController`. Keep the controller thin — auth + map + delegate. Do NOT re-check dispute state in the controller; `applyRuling` owns first-ruling-wins.)

- [ ] **Step 6: Run the controller test, then the full build**

Run: `mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ArbitrationCallbackControllerTest -Dsurefire.failIfNoSpecifiedTests=false`
Then: `mvn -f backend/pom.xml -q -B test`
Expected: PASS then BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add backend/hireai-controller backend/hireai-main
git commit -m "feat(adjudication): arbitration ruling callback (shared-secret, first-ruling-wins)"
```

---

### Task 3: DLQ refund-fallback listener

Guarantees termination: an exhausted/poison arbitration request dead-letters to a refund. (兜底)

**Files:**
- Create: `backend/hireai-infrastructure/src/main/java/com/hireai/infrastructure/messaging/ArbitrationDlqListener.java`
- Test: `backend/hireai-infrastructure/src/test/java/com/hireai/infrastructure/messaging/ArbitrationDlqListenerTest.java`

**Interfaces:**
- Consumes: `ArbitrationRequestMessage` (off the DLQ), `DisputeAppService.resolveByFallback(UUID disputeId)`.

> DESIGN NOTE — the listener is `@Profile("!test")` (mirrors the adapter — it only runs where Rabbit is active) and `@RabbitListener(queues = ArbitrationQueues.DLQ)`. Its handler takes an `ArbitrationRequestMessage` (Jackson-deserialized) and calls `disputeAppService.resolveByFallback(message.disputeId())`. `resolveByFallback` already no-ops if the dispute is already resolved, so a redelivered DLQ message is safe.

- [ ] **Step 1: Write the failing test** (call the handler method directly with an `ArbitrationRequestMessage`; mock `DisputeAppService`; verify `resolveByFallback(disputeId)` invoked)

```java
// ArbitrationDlqListenerTest.java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class ArbitrationDlqListenerTest {

    private final DisputeAppService disputeAppService = mock(DisputeAppService.class);
    private final ArbitrationDlqListener listener = new ArbitrationDlqListener(disputeAppService);

    @Test
    void deadLetteredRequestTriggersRefundFallback() {
        UUID disputeId = UUID.randomUUID();
        ArbitrationRequestMessage msg = new ArbitrationRequestMessage(
                disputeId, UUID.randomUUID(), "corr", "JSON", null, null, "{}", null, "A_MISMATCH");
        listener.onDeadLetter(msg);
        verify(disputeAppService).resolveByFallback(disputeId);
    }
}
```

- [ ] **Step 2: Run it, verify it fails; implement the listener; run + full build; commit**

Listener:
```java
// ArbitrationDlqListener.java
package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 兜底: an exhausted/poison arbitration request dead-letters here → full refund to the client + dispute RESOLVED. */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ArbitrationDlqListener {

    private final DisputeAppService disputeAppService;

    @RabbitListener(queues = ArbitrationQueues.DLQ)
    public void onDeadLetter(ArbitrationRequestMessage message) {
        log.warn("Arbitration request dead-lettered for dispute {} (correlation {}); refund-fallback",
                message.disputeId(), message.correlationId());
        disputeAppService.resolveByFallback(message.disputeId());
    }
}
```

Commit: `git add backend/hireai-infrastructure && git commit -m "feat(adjudication): DLQ refund-fallback listener for arbitration (兜底)"`

---

### Task 4: Transport integration test (Testcontainers Postgres + RabbitMQ)

End-to-end over a real broker, in a non-`test` profile so the **Rabbit** adapter is active: open a dispute (publishes + ARBITRATING), simulate the worker via the callback (settle), and exercise the DLQ fallback + the callback auth.

**Files:**
- Create: `backend/hireai-main/src/test/java/com/hireai/adjudication/ArbitrationTransportIntegrationTest.java`

> SETUP NOTE — this test must NOT use `@ActiveProfiles("test")` (that activates the stub). Boot the default/secured profile with Testcontainers Postgres **+ RabbitMQ** via `@DynamicPropertySource` (mirror the EXISTING dispatch round-trip IT — find it, e.g. `RoutingIntegrationTest`/`DispatchRoundTripIntegrationTest`, and copy its Rabbit container + property wiring AND its security handling). Provide the callback secret via `@DynamicPropertySource`/`@TestPropertySource` (`hireai.arbitration.callback-secret`). Seed a funded client wallet + a distinct builder-owned agent + a task at `PENDING_REVIEW` exactly as `DisputeFlowIntegrationTest` does (reuse that seeding). Because the secured chain enforces JWT, drive `reject`/dispute-open via the app service directly (or mint a JWT), and hit ONLY the callback over HTTP (it is `permitAll` + secret-gated).

**Scenarios (all `@EnabledIf("dockerAvailable")` so they skip without Docker):**
1. **Round-trip:** open a dispute (via `reviewAppService.reject(A_MISMATCH)` or `disputeAppService` directly) → assert the dispute is `ARBITRATING`, task `DISPUTED`, escrow still held, NO settlement yet, and a message is on `task.dispute.requested` (or assert the publish happened). Then POST the callback `{category:"NOT_FULFILLED", rationale:"x"}` with the secret → assert dispute `RESOLVED`, task `RESOLVED`, client refunded in full, settlement row `REJECT`.
2. **First-ruling-wins:** a second callback POST on the now-RESOLVED dispute → `200`, no double settlement (wallet unchanged).
3. **Auth:** callback with a wrong/missing secret → `401`, dispute stays `ARBITRATING`.
4. **DLQ fallback:** publish an `ArbitrationRequestMessage` (for an `ARBITRATING` dispute) DIRECTLY to `ArbitrationQueues.DLQ` (via the test `RabbitTemplate`) → await the `ArbitrationDlqListener` → assert the dispute `RESOLVED` by fallback + the client fully refunded.

- [ ] **Step 1: Write the integration test** per the scenarios above (use Awaitility or a bounded poll for the async DLQ assertion — mirror how the dispatch round-trip IT awaits async delivery).
- [ ] **Step 2: Run it** (`mvn -f backend/pom.xml -pl hireai-main -am test -Dtest=ArbitrationTransportIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`) — PASS with Docker, SKIP without.
- [ ] **Step 3: Full build** (`mvn -f backend/pom.xml -q -B test`) — BUILD SUCCESS.
- [ ] **Step 4: Commit** (`git add backend/hireai-main && git commit -m "test(adjudication): arbitration transport round-trip + DLQ fallback + callback auth"`)

---

## Self-review checklist (run before dispatching execution)

1. **Spec coverage** — §6 outbound publish + ARBITRATING (Task 1); shared-secret first-ruling-wins callback (Task 2); DLQ → refund 兜底 (Task 3); round-trip + auth + DLQ integration (Task 4). The Python worker is explicitly Phase 3b.
2. **No Phase-2 regression** — the stub stays the `test`-profile bean (Task 1 Step 7), so every `@ActiveProfiles("test")` Phase-2 IT keeps the synchronous behavior it asserts. The only adapter-selection change is the stub annotation; verify the full build is green after Task 1.
3. **Type consistency** — `ArbitrationRequestMessage` fields identical across publisher, DLQ listener, and the IT; `applyRuling(UUID, RulingInfo)` / `resolveByFallback(UUID)` match the Phase-2 `DisputeAppService` signatures; `ArbitrationQueues` constants used identically in config, publisher, listener, IT.
4. **Invariants** — Inv #6: callback secret constant-time compared, `permitAll` route gated solely by the secret, 401 on failure. Inv #3: callback passes only `{category, rationale}`; settlement unchanged. Every dispute terminates (ruling via callback, or DLQ→refund) — no stranded escrow.
5. **No placeholders** — config/security/IT boilerplate is delegated to clearly-flagged "mirror the real X" implementer notes (proven in Phase 2); everything novel (message, publisher, profile gating, callback contract, DLQ handler) has complete code.
