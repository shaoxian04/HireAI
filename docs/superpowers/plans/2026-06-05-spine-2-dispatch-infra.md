# Dispatch Infrastructure + Stub Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Module 3 / Track B dispatch infrastructure — a signed-token service, an HTTPS-only webhook dispatch client, RabbitMQ wiring (exchange/queue/binding + DLQ/DLX with bounded retry), a publish/consume pair, and a standalone stub Agent — so a routed `DispatchMessage` is delivered to a registered Agent over a signed HTTPS webhook and the Agent can post a result back.

**Architecture:** Everything in this track lives in `infrastructure/messaging`, `infrastructure/client`, and `infrastructure/security`, plus a `demo-agent/` stub process. The track depends ONLY on the contracts-first seam types committed by Plan 0 (`TaskDispatchPublisher`, `DispatchTokenService`, `TaskExecutionPort`, `DispatchMessage`, `TaskDispatchPayload`, `DispatchTokenClaims`, `DispatchTokenInvalidException`). It imports NO Plan 1/Plan 3 concrete classes; the consumer flips task status through the `TaskExecutionPort` port, so in this worktree a tiny stub `@Component` implements that port for tests. Plan 2 owns the two shared-file edits: `backend/pom.xml` (add `spring-boot-starter-amqp`, test-scope `rabbitmq` Testcontainer) and `backend/src/main/resources/application.yml` (RabbitMQ connection + dispatch config).

**Tech Stack:** Java 21, Spring Boot 3.x (3.3.5), Spring AMQP (RabbitMQ), Spring `RestClient`, Jackson, JPA/Hibernate, Flyway, PostgreSQL, JUnit 5 + Testcontainers (Postgres + RabbitMQ), Python 3 + FastAPI (stub agent).

---

## Conventions applied (read before starting)

- **DDD layering** `controller → application → domain ← infrastructure`. Plan 2 lives entirely in `infrastructure/**` (+ `demo-agent/`, `pom.xml`, `application.yml`). It depends on **application-layer ports** (`com.hireai.application.port.**`) and **domain records** (`com.hireai.domain.biz.routing.info.**`), both committed by Plan 0. It never imports Plan 1 (`agent`) or Plan 3 (`task`/`agentcallback`) concrete classes.
- **Service classes are interface + `impl/`.** The seam interfaces (`TaskDispatchPublisher`, `DispatchTokenService`) are owned by Plan 0; this plan supplies their `impl/` classes under `infrastructure/messaging/impl` and `infrastructure/security/impl`. Impl classes carry `@Service`/`@Component`, `@Slf4j`, `@RequiredArgsConstructor`.
- **Domain layer purity is irrelevant here** — Plan 2 has no domain code. All Plan 2 classes are Spring-managed infrastructure beans.
- **Money is `BigDecimal`** (none flows through this track directly; `DispatchMessage` carries no money).
- **Config from env/properties**, never hardcoded secrets. The HMAC secret comes from `hireai.dispatch.token-secret` (env `DISPATCH_TOKEN_SECRET`).
- **All `mvn` commands** run from the repo root against the backend module: `mvn -f backend/pom.xml ...`.
- **Integration tests** named `*IntegrationTest` boot Testcontainers and auto-skip without Docker via `@EnabledIf("dockerAvailable")` (mirror `TaskSubmissionIntegrationTest`).
- **Conventional-commit messages** (`feat:`/`test:`/`chore:`). Do NOT add `Co-Authored-By` lines.

## Pre-flight: contracts this track consumes (created by Plan 0, already on the branch)

Do NOT create these — they exist when this worktree branches. Verify they compile (`mvn -f backend/pom.xml -q -B test-compile`) before Task 1:

```
com.hireai.domain.biz.routing.info.TaskDispatchPayload
  record TaskDispatchPayload(String title, String description, String category,
                             String expectedDeliverableJson, String outputSpecJson, String callbackUrl) {}
com.hireai.domain.biz.routing.info.DispatchMessage
  record DispatchMessage(UUID taskId, UUID agentVersionId, String webhookUrl,
                         String correlationId, TaskDispatchPayload payload) {}
com.hireai.application.port.messaging.TaskDispatchPublisher
  void publish(DispatchMessage message);
com.hireai.application.port.security.DispatchTokenService
  String issue(UUID taskId, UUID agentVersionId, java.time.Duration ttl);
  DispatchTokenClaims verify(String token);   // throws DispatchTokenInvalidException
com.hireai.application.port.security.DispatchTokenClaims
  record DispatchTokenClaims(UUID taskId, UUID agentVersionId, java.time.Instant expiresAt) {}
com.hireai.application.port.security.DispatchTokenInvalidException extends RuntimeException
  // Plan 0 provides a public (String message) constructor — this track calls
  // new DispatchTokenInvalidException("...") in HmacDispatchTokenService.verify.
com.hireai.application.port.task.TaskExecutionPort
  void markExecuting(UUID taskId);
  void markTimedOut(UUID taskId);
  void markFailed(UUID taskId);
```

If `DispatchTokenInvalidException` lacks a `(String)` constructor on the branch (Plan 0 only guaranteed `extends RuntimeException`), the executing agent must either confirm Plan 0 added it, or change the four `new DispatchTokenInvalidException("...")` calls in Task 2 to the no-arg form `new DispatchTokenInvalidException()`. Do NOT add the constructor here — that file is owned by Plan 0.

## File structure

**New files (main):**
```
backend/src/main/java/com/hireai/
  infrastructure/security/impl/HmacDispatchTokenService.java
  infrastructure/client/AgentDispatchClient.java
  infrastructure/client/WebhookDispatchBody.java
  infrastructure/messaging/DispatchQueues.java
  infrastructure/messaging/RabbitDispatchConfig.java
  infrastructure/messaging/impl/RabbitTaskDispatchPublisher.java
  infrastructure/messaging/TaskDispatchConsumer.java
backend/src/main/resources/
  db/migration/                     (none — Plan 3 owns V4)
demo-agent/
  app.py
  requirements.txt
  README.md
```

**New files (test):**
```
backend/src/test/java/com/hireai/
  infrastructure/security/HmacDispatchTokenServiceTest.java
  infrastructure/client/AgentDispatchClientTest.java
  dispatch/StubTaskExecutionPort.java
  dispatch/DispatchRoundTripIntegrationTest.java
```

**Modified files (Plan 2 owns these two shared-file edits):**
```
backend/pom.xml                                   (add spring-boot-starter-amqp + test-scope rabbitmq Testcontainer)
backend/src/main/resources/application.yml        (RabbitMQ connection + hireai.dispatch.*)
```

---

## Task 1 — Add Spring AMQP + RabbitMQ Testcontainer dependency

Plan 2 owns `pom.xml`. Add the AMQP starter (main) and the RabbitMQ Testcontainer (test). No test drives this directly; the gate is a clean compile, and Task 7's integration test proves the wiring.

**Files:**
- Modify: `backend/pom.xml`

### Steps

- [ ] Add the AMQP starter dependency. Insert immediately after the `spring-boot-starter-security` dependency block (before the Lombok block):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
```

- [ ] Add the RabbitMQ Testcontainer (test scope). Insert immediately after the `org.testcontainers:junit-jupiter` test dependency (the BOM in `dependencyManagement` already pins the version, so no `<version>` here):

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] Verify the project still compiles with the new dependencies on the classpath:

```
mvn -f backend/pom.xml -q -B test-compile
```

Expected: BUILD SUCCESS (no source changes yet, dependencies resolve).

- [ ] Commit:

```
git add backend/pom.xml
git commit -m "chore: add spring-boot-starter-amqp and rabbitmq testcontainer"
```

---

## Task 2 — HMAC dispatch token service (issue + verify)

Implements the Plan 0 `DispatchTokenService` port: a compact, self-contained HMAC-SHA256 token carrying `{taskId, agentVersionId, exp}`. `verify` rejects a tampered signature, an expired token, and (via claims) a taskId mismatch (the mismatch check itself happens in Plan 3's callback service comparing claims to the path taskId; this service guarantees the claims are authentic and unexpired).

Token format (compact, URL-safe, dot-separated): `base64url(payloadJson) + "." + base64url(hmacSha256(payloadJson))`, where `payloadJson = {"taskId":"...","agentVersionId":"...","exp":<epochSeconds>}`.

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/security/impl/HmacDispatchTokenService.java`
- Test: `backend/src/test/java/com/hireai/infrastructure/security/HmacDispatchTokenServiceTest.java`

### Steps

- [ ] Write the failing test. It constructs the service directly with a fixed secret (no Spring), covering happy round-trip, tampered signature, and expiry:

```java
package com.hireai.infrastructure.security;

import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.infrastructure.security.impl.HmacDispatchTokenService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacDispatchTokenServiceTest {

    private final HmacDispatchTokenService service =
            new HmacDispatchTokenService("test-secret-test-secret-test-secret-32b");

    @Test
    void issuesTokenThatVerifiesToTheSameClaims() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();

        String token = service.issue(taskId, agentVersionId, Duration.ofMinutes(5));
        DispatchTokenClaims claims = service.verify(token);

        assertThat(claims.taskId()).isEqualTo(taskId);
        assertThat(claims.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(claims.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    void rejectsTokenWithTamperedSignature() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(5));
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsTokenSignedWithADifferentSecret() {
        HmacDispatchTokenService other =
                new HmacDispatchTokenService("OTHER-secret-OTHER-secret-OTHER-secret-32");
        String token = other.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofMinutes(5));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofSeconds(-1));

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(DispatchTokenInvalidException.class);
    }
}
```

- [ ] Run it (expect FAIL — `HmacDispatchTokenService` does not exist yet, compile error):

```
mvn -f backend/pom.xml -B test -Dtest=HmacDispatchTokenServiceTest
```

Expected: FAIL (compilation error: cannot find symbol `HmacDispatchTokenService`).

- [ ] Implement the service:

```java
package com.hireai.infrastructure.security.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenInvalidException;
import com.hireai.application.port.security.DispatchTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * HMAC-SHA256 compact dispatch token: {@code base64url(payloadJson).base64url(signature)}.
 * Payload carries {@code taskId, agentVersionId, exp (epoch seconds)}. The secret is a
 * server-side env value; tokens are short-lived and never stored. {@code verify} rejects a
 * bad signature (constant-time compare) and expiry by throwing {@link DispatchTokenInvalidException}.
 */
@Service
@Slf4j
public class HmacDispatchTokenService implements DispatchTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] secretKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HmacDispatchTokenService(@Value("${hireai.dispatch.token-secret}") String tokenSecret) {
        if (tokenSecret == null || tokenSecret.length() < 16) {
            throw new IllegalStateException("hireai.dispatch.token-secret must be at least 16 characters");
        }
        this.secretKey = tokenSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(UUID taskId, UUID agentVersionId, Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("taskId", taskId.toString());
        payload.put("agentVersionId", agentVersionId.toString());
        payload.put("exp", exp);
        String payloadJson = payload.toString();
        String encodedPayload = URL_ENCODER.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signature = URL_ENCODER.encodeToString(sign(encodedPayload));
        return encodedPayload + "." + signature;
    }

    @Override
    public DispatchTokenClaims verify(String token) {
        if (token == null) {
            throw new DispatchTokenInvalidException("Token missing");
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new DispatchTokenInvalidException("Malformed token");
        }
        String encodedPayload = token.substring(0, dot);
        String providedSignature = token.substring(dot + 1);

        byte[] expected = sign(encodedPayload);
        byte[] provided;
        try {
            provided = URL_DECODER.decode(providedSignature);
        } catch (IllegalArgumentException ex) {
            throw new DispatchTokenInvalidException("Malformed signature");
        }
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new DispatchTokenInvalidException("Signature mismatch");
        }

        try {
            String payloadJson = new String(URL_DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
            var node = objectMapper.readTree(payloadJson);
            UUID taskId = UUID.fromString(node.get("taskId").asText());
            UUID agentVersionId = UUID.fromString(node.get("agentVersionId").asText());
            Instant expiresAt = Instant.ofEpochSecond(node.get("exp").asLong());
            if (expiresAt.isBefore(Instant.now())) {
                throw new DispatchTokenInvalidException("Token expired");
            }
            return new DispatchTokenClaims(taskId, agentVersionId, expiresAt);
        } catch (DispatchTokenInvalidException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DispatchTokenInvalidException("Unparseable token payload");
        }
    }

    private byte[] sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to compute HMAC", ex);
        }
    }
}
```

- [ ] Run it (expect PASS):

```
mvn -f backend/pom.xml -B test -Dtest=HmacDispatchTokenServiceTest
```

Expected: PASS (4 tests green).

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/security/impl/HmacDispatchTokenService.java backend/src/test/java/com/hireai/infrastructure/security/HmacDispatchTokenServiceTest.java
git commit -m "feat: HMAC-SHA256 dispatch token service (issue + verify)"
```

---

## Task 3 — Webhook dispatch body DTO (wire contract B)

The exact JSON shape POSTed to the Agent's webhook (CONTRACTS wire contract B). `expectedDeliverable` and `outputSpec` are raw JSON values, so they are typed as `com.fasterxml.jackson.databind.JsonNode` and parsed from the payload's JSON strings, NOT re-quoted as strings.

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/client/WebhookDispatchBody.java`
- (No standalone test; exercised by Task 4's `AgentDispatchClientTest`.)

### Steps

- [ ] Implement the record:

```java
package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * JSON body POSTed to an Agent's webhook (CONTRACTS wire contract B). {@code expectedDeliverable}
 * and {@code outputSpec} are embedded as raw JSON (not re-quoted strings), so they are typed as
 * {@link JsonNode}; {@link AgentDispatchClient} parses them from the {@code DispatchMessage} payload's
 * JSON strings before sending.
 */
public record WebhookDispatchBody(
        UUID taskId,
        String category,
        String title,
        String description,
        JsonNode expectedDeliverable,
        JsonNode outputSpec,
        String callbackUrl) {
}
```

- [ ] Verify it compiles:

```
mvn -f backend/pom.xml -q -B test-compile
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/client/WebhookDispatchBody.java
git commit -m "feat: webhook dispatch body DTO (wire contract B)"
```

---

## Task 4 — AgentDispatchClient (HTTPS-only signed webhook POST)

A `RestClient`-based client that POSTs `WebhookDispatchBody` to the Agent's `webhookUrl`, setting `Authorization: Bearer <token>` and `X-Correlation-ID`. It **rejects any non-`https://` URL by throwing** (Hard Invariant #6), with a `dev`-profile localhost exception so the demo runs without a public HTTPS tunnel. Connect/read timeouts are bounded.

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/client/AgentDispatchClient.java`
- Test: `backend/src/test/java/com/hireai/infrastructure/client/AgentDispatchClientTest.java`

### Steps

- [ ] Write the failing test. It uses `MockRestServiceServer` to assert the request shape for HTTPS, and asserts a thrown exception for a non-HTTPS URL (prod-profile behaviour, `devProfile=false`):

```java
package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class AgentDispatchClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DispatchMessage messageTo(String webhookUrl) {
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise report", "Summarise the attached quarterly report", "summarisation",
                "{\"type\":\"string\"}", "{\"format\":\"JSON\"}", "https://platform.test/api/agent-callbacks/x/result");
        return new DispatchMessage(UUID.randomUUID(), UUID.randomUUID(), webhookUrl, "corr-123", payload);
    }

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void postsSignedBodyToHttpsWebhook() {
        String webhook = "https://agent.example.com/run";
        server.expect(requestTo(webhook))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer the-token"))
                .andExpect(header("X-Correlation-ID", "corr-123"))
                .andExpect(jsonPath("$.category").value("summarisation"))
                .andExpect(jsonPath("$.callbackUrl")
                        .value("https://platform.test/api/agent-callbacks/x/result"))
                .andExpect(jsonPath("$.outputSpec.format").value("JSON"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = new AgentDispatchClient(builder, objectMapper, false);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonHttpsWebhookInProdProfile() {
        AgentDispatchClient client = new AgentDispatchClient(RestClient.builder(), objectMapper, false);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void allowsHttpLocalhostInDevProfile() {
        String webhook = "http://localhost:9000/run";
        server.expect(requestTo(webhook))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));

        AgentDispatchClient client = new AgentDispatchClient(builder, objectMapper, true);
        client.dispatch(messageTo(webhook), "the-token");

        server.verify();
    }

    @Test
    void rejectsNonLocalhostHttpEvenInDevProfile() {
        AgentDispatchClient client = new AgentDispatchClient(RestClient.builder(), objectMapper, true);

        assertThatThrownBy(() -> client.dispatch(messageTo("http://agent.example.com/run"), "the-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
```

- [ ] Run it (expect FAIL — `AgentDispatchClient` does not exist):

```
mvn -f backend/pom.xml -B test -Dtest=AgentDispatchClientTest
```

Expected: FAIL (compilation error: cannot find symbol `AgentDispatchClient`).

- [ ] Implement the client. The `devProfile` flag is bound from `hireai.dispatch.allow-insecure-localhost` (true only in the `dev` profile); the test passes it directly via the constructor:

```java
package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * Dispatches a {@link DispatchMessage} to an Agent's webhook over a signed HTTPS POST
 * (CONTRACTS wire contract B). Enforces Hard Invariant #6: a non-HTTPS webhook URL is
 * rejected by throwing, except an {@code http://localhost} URL when the dev-profile flag
 * {@code hireai.dispatch.allow-insecure-localhost} is true (the signed-token check still
 * applies in every profile). Connect/read timeouts are bounded.
 */
@Component
@Slf4j
public class AgentDispatchClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean allowInsecureLocalhost;

    public AgentDispatchClient(RestClient.Builder restClientBuilder,
                               ObjectMapper objectMapper,
                               @Value("${hireai.dispatch.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.restClient = restClientBuilder.requestFactory(timeoutRequestFactory()).build();
        this.objectMapper = objectMapper;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    public void dispatch(DispatchMessage message, String token) {
        String webhookUrl = message.webhookUrl();
        requireSecureWebhook(webhookUrl);
        WebhookDispatchBody body = buildBody(message);

        restClient.post()
                .uri(URI.create(webhookUrl))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-ID", message.correlationId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Dispatched task {} to webhook (correlationId={})", message.taskId(), message.correlationId());
    }

    private WebhookDispatchBody buildBody(DispatchMessage message) {
        TaskDispatchPayload payload = message.payload();
        return new WebhookDispatchBody(
                message.taskId(),
                payload.category(),
                payload.title(),
                payload.description(),
                readJson(payload.expectedDeliverableJson()),
                readJson(payload.outputSpecJson()),
                payload.callbackUrl());
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Dispatch payload contains malformed JSON", ex);
        }
    }

    private void requireSecureWebhook(String webhookUrl) {
        if (webhookUrl == null) {
            throw new IllegalArgumentException("Webhook URL must not be null");
        }
        if (webhookUrl.startsWith("https://")) {
            return;
        }
        if (allowInsecureLocalhost && isHttpLocalhost(webhookUrl)) {
            log.warn("Dispatching to insecure localhost webhook {} (dev profile)", webhookUrl);
            return;
        }
        throw new IllegalArgumentException("Webhook URL must use HTTPS: " + webhookUrl);
    }

    private boolean isHttpLocalhost(String webhookUrl) {
        try {
            URI uri = URI.create(webhookUrl);
            String host = uri.getHost();
            return "http".equals(uri.getScheme())
                    && ("localhost".equals(host) || "127.0.0.1".equals(host));
        } catch (Exception ex) {
            return false;
        }
    }

    private ClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return factory;
    }
}
```

- [ ] Run it (expect PASS):

```
mvn -f backend/pom.xml -B test -Dtest=AgentDispatchClientTest
```

Expected: PASS (4 tests green).

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/client/AgentDispatchClient.java backend/src/test/java/com/hireai/infrastructure/client/AgentDispatchClientTest.java
git commit -m "feat: AgentDispatchClient HTTPS-only signed webhook POST"
```

---

## Task 5 — RabbitMQ topology: DispatchQueues constants + RabbitDispatchConfig

`DispatchQueues` is the single class of string constants (CONTRACTS). `RabbitDispatchConfig` declares the exchange, queue, binding, DLX/DLQ, a bounded retry via per-message TTL on the DLQ (messages dead-letter to DLQ, sit for a TTL, then route back to the main exchange for a retry; after a hop limit they stay in the DLQ for the DLQ listener), and a `Jackson2JsonMessageConverter` so `DispatchMessage` serialises as JSON.

Retry policy: the main queue dead-letters rejected messages to `DLX`→`DLQ`. The `RabbitTaskDispatchPublisher` (Task 6) and listener container use the converter. The DLQ has its own listener (Task 6) which calls `TaskExecutionPort.markTimedOut`/`markFailed`. We keep retry bounded by Spring's listener-side retry (configured in `application.yml`, Task 7) rather than TTL-replay loops, which is simpler and sufficient for the demo; the DLX/DLQ captures messages whose listener retries are exhausted.

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/messaging/DispatchQueues.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/messaging/RabbitDispatchConfig.java`
- (No standalone unit test; the topology is proven by Task 7's integration test.)

### Steps

- [ ] Create the constants class (names verbatim from CONTRACTS):

```java
package com.hireai.infrastructure.messaging;

/**
 * RabbitMQ topology names for task dispatch (CONTRACTS — Plan 2 owns these; routing never
 * references them, it uses the {@code TaskDispatchPublisher} port). The main queue
 * dead-letters to the DLX/DLQ when listener retries are exhausted.
 */
public final class DispatchQueues {

    public static final String EXCHANGE = "task.dispatch.exchange";
    public static final String QUEUE = "task.dispatch";
    public static final String ROUTING_KEY = "task.dispatch";
    public static final String DLQ = "task.dispatch.dlq";
    public static final String DLX = "task.dispatch.dlx";

    private DispatchQueues() {
    }
}
```

- [ ] Create the config. It declares durable queues, binds the main queue to the dispatch exchange and the DLQ to the DLX, sets the main queue's dead-letter exchange to `DLX`, and registers the JSON converter plus a `RabbitTemplate` using it:

```java
package com.hireai.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the task-dispatch RabbitMQ topology: a direct exchange + durable queue bound on
 * {@link DispatchQueues#ROUTING_KEY}, a dead-letter exchange/queue, and a JSON message
 * converter so {@code DispatchMessage} is serialised as JSON. The main queue dead-letters
 * to {@link DispatchQueues#DLX} when listener retries are exhausted; the DLQ has its own
 * listener (see {@code TaskDispatchConsumer}).
 */
@Configuration
public class RabbitDispatchConfig {

    @Bean
    public DirectExchange dispatchExchange() {
        return new DirectExchange(DispatchQueues.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dispatchDeadLetterExchange() {
        return new DirectExchange(DispatchQueues.DLX, true, false);
    }

    @Bean
    public Queue dispatchQueue() {
        return QueueBuilder.durable(DispatchQueues.QUEUE)
                .deadLetterExchange(DispatchQueues.DLX)
                .deadLetterRoutingKey(DispatchQueues.DLQ)
                .build();
    }

    @Bean
    public Queue dispatchDeadLetterQueue() {
        return QueueBuilder.durable(DispatchQueues.DLQ).build();
    }

    @Bean
    public Binding dispatchBinding(Queue dispatchQueue, DirectExchange dispatchExchange) {
        return BindingBuilder.bind(dispatchQueue).to(dispatchExchange).with(DispatchQueues.ROUTING_KEY);
    }

    @Bean
    public Binding dispatchDeadLetterBinding(Queue dispatchDeadLetterQueue,
                                             DirectExchange dispatchDeadLetterExchange) {
        return BindingBuilder.bind(dispatchDeadLetterQueue).to(dispatchDeadLetterExchange).with(DispatchQueues.DLQ);
    }

    @Bean
    public MessageConverter dispatchMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate dispatchRabbitTemplate(ConnectionFactory connectionFactory,
                                                 MessageConverter dispatchMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(dispatchMessageConverter);
        template.setExchange(DispatchQueues.EXCHANGE);
        template.setRoutingKey(DispatchQueues.ROUTING_KEY);
        return template;
    }
}
```

- [ ] Verify it compiles:

```
mvn -f backend/pom.xml -q -B test-compile
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/messaging/DispatchQueues.java backend/src/main/java/com/hireai/infrastructure/messaging/RabbitDispatchConfig.java
git commit -m "feat: RabbitMQ dispatch topology (exchange, queue, binding, DLX/DLQ, JSON converter)"
```

---

## Task 6 — Publisher + Consumer + DLQ listener

`RabbitTaskDispatchPublisher` implements the `TaskDispatchPublisher` port: converts and sends `DispatchMessage` to the dispatch exchange. `TaskDispatchConsumer` is `@RabbitListener` on `DispatchQueues.QUEUE`: it issues a token (`ttl = maxExecutionSeconds + buffer`; since `DispatchMessage` does not carry `maxExecutionSeconds`, the consumer uses a fixed bounded TTL from config), calls `AgentDispatchClient.dispatch`, then `TaskExecutionPort.markExecuting`. A second `@RabbitListener` on `DispatchQueues.DLQ` calls `TaskExecutionPort.markFailed` (or `markTimedOut` for read-timeout causes). Plan 2 depends ONLY on the `TaskExecutionPort` port; the consumer never imports Plan 3 classes.

Token TTL note: the design's `ttl = maxExecutionSeconds + buffer` needs the version's `maxExecutionSeconds`, which is not on `DispatchMessage`. To stay within the fixed contract, the consumer reads a single configured ceiling `hireai.dispatch.token-ttl-seconds` (default 900) as the token TTL — large enough to cover any agent's `maxExecutionSeconds` for the demo. (A future hardening step can add `maxExecutionSeconds` to `DispatchMessage`; that is a Plan 0 contract change, out of scope here.)

**Files:**
- Create: `backend/src/main/java/com/hireai/infrastructure/messaging/impl/RabbitTaskDispatchPublisher.java`
- Create: `backend/src/main/java/com/hireai/infrastructure/messaging/TaskDispatchConsumer.java`
- (Behaviour proven by Task 7's integration test; no standalone unit test — the publisher/consumer need a broker.)

### Steps

- [ ] Implement the publisher:

```java
package com.hireai.infrastructure.messaging.impl;

import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.infrastructure.messaging.DispatchQueues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Publishes a {@link DispatchMessage} onto the task-dispatch exchange (implements the
 * Plan 0 {@code TaskDispatchPublisher} port). The JSON converter on the injected template
 * serialises the message; routing supplies the message via the port and never sees RabbitMQ.
 */
@Service
@Slf4j
public class RabbitTaskDispatchPublisher implements TaskDispatchPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitTaskDispatchPublisher(@Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DispatchMessage message) {
        rabbitTemplate.convertAndSend(DispatchQueues.EXCHANGE, DispatchQueues.ROUTING_KEY, message);
        log.info("Published dispatch for task {} (correlationId={})", message.taskId(), message.correlationId());
    }
}
```

- [ ] Implement the consumer + DLQ listener:

```java
package com.hireai.infrastructure.messaging;

import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.infrastructure.client.AgentDispatchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes dispatch messages: issues a short-lived signed token, POSTs the webhook via
 * {@link AgentDispatchClient}, then flips the task to EXECUTING through the
 * {@link TaskExecutionPort} port. A thrown exception is re-raised so the listener container's
 * bounded retry applies; on exhaustion the message dead-letters to {@link DispatchQueues#DLQ},
 * where the DLQ listener marks the task FAILED. Plan 2 depends only on the port — no Plan 3 imports.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskDispatchConsumer {

    private final DispatchTokenService dispatchTokenService;
    private final AgentDispatchClient agentDispatchClient;
    private final TaskExecutionPort taskExecutionPort;

    @Value("${hireai.dispatch.token-ttl-seconds:900}")
    private long tokenTtlSeconds;

    @RabbitListener(queues = DispatchQueues.QUEUE)
    public void onDispatch(DispatchMessage message) {
        String token = dispatchTokenService.issue(
                message.taskId(), message.agentVersionId(), Duration.ofSeconds(tokenTtlSeconds));
        agentDispatchClient.dispatch(message, token);
        taskExecutionPort.markExecuting(message.taskId());
        log.info("Task {} dispatched and marked EXECUTING", message.taskId());
    }

    @RabbitListener(queues = DispatchQueues.DLQ)
    public void onDeadLetter(DispatchMessage message) {
        log.warn("Dispatch for task {} exhausted retries; marking FAILED", message.taskId());
        taskExecutionPort.markFailed(message.taskId());
    }
}
```

- [ ] Verify it compiles:

```
mvn -f backend/pom.xml -q -B test-compile
```

Expected: BUILD SUCCESS.

- [ ] Commit:

```
git add backend/src/main/java/com/hireai/infrastructure/messaging/impl/RabbitTaskDispatchPublisher.java backend/src/main/java/com/hireai/infrastructure/messaging/TaskDispatchConsumer.java
git commit -m "feat: dispatch publisher, consumer, and DLQ listener"
```

---

## Task 7 — RabbitMQ connection config + publish→consume integration test

Add the RabbitMQ connection settings and `hireai.dispatch.*` config to `application.yml` (Plan 2 owns this file). Then a Testcontainers integration test (Postgres + RabbitMQ, auto-skip without Docker) publishes a `DispatchMessage` through the real `TaskDispatchPublisher` bean and asserts the consumer issues a token, dispatches over an HTTPS webhook (a `MockWebServer`/embedded stub), and calls `TaskExecutionPort.markExecuting`. The port is satisfied in-test by `StubTaskExecutionPort` (a `@Primary` test bean) — Plan 3's concrete impl is NOT in this worktree.

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/hireai/dispatch/StubTaskExecutionPort.java`
- Create: `backend/src/test/java/com/hireai/dispatch/DispatchRoundTripIntegrationTest.java`

### Steps

- [ ] Add RabbitMQ + dispatch config to `application.yml`. Append a `rabbitmq` block under `spring:` and a top-level `hireai:` block. The full file becomes:

```yaml
spring:
  application:
    name: hireai-backend
  # Optionally load a local, git-ignored backend/.env (KEY=VALUE) so DB_URL etc.
  # resolve from it. "optional:" means startup still works when the file is absent
  # (CI, tests). Integration tests override the datasource via @DynamicPropertySource,
  # so a present .env does not affect them.
  config:
    import: "optional:file:.env[.properties]"
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/hireai}
    username: ${DB_USERNAME:hireai}
    password: ${DB_PASSWORD:hireai}
  jpa:
    hibernate:
      # Never let Hibernate touch DDL — schema is owned by Flyway migrations.
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        # Bounded listener-side retry; on exhaustion the message dead-letters to the DLQ.
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000
          multiplier: 2.0
          max-interval: 10000
        default-requeue-rejected: false

server:
  port: 8080

logging:
  level:
    root: INFO

hireai:
  # Public base URL the platform advertises to Agents for result callbacks (Plan 4's routing
  # builds callbackUrl = <this> + /api/agent-callbacks/{taskId}/result). Owned here in Plan 2's
  # application.yml; consumed by Plan 4's RoutingAppServiceImpl. Localhost default for the demo.
  platform:
    public-base-url: ${PUBLIC_BASE_URL:http://localhost:8080}
  dispatch:
    # HMAC secret for short-lived dispatch tokens. MUST be overridden in real deploys.
    token-secret: ${DISPATCH_TOKEN_SECRET:dev-only-dispatch-secret-change-me-32b}
    token-ttl-seconds: ${DISPATCH_TOKEN_TTL_SECONDS:900}
    # Allow http://localhost webhooks only when explicitly enabled (dev/demo). HTTPS is
    # enforced in every other case (Hard Invariant #6).
    allow-insecure-localhost: ${DISPATCH_ALLOW_INSECURE_LOCALHOST:false}
```

- [ ] Create the stub `TaskExecutionPort` test bean. It records calls so the test can assert `markExecuting` fired:

```java
package com.hireai.dispatch;

import com.hireai.application.port.task.TaskExecutionPort;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-test implementation of the Plan 0 {@link TaskExecutionPort}. Plan 3's concrete impl is not
 * in this worktree, so the dispatch consumer is wired against this recorder. {@code @Primary}
 * ensures it wins over any other candidate on the test classpath.
 */
@TestComponent
@Primary
public class StubTaskExecutionPort implements TaskExecutionPort {

    public final CopyOnWriteArrayList<UUID> executing = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<UUID> timedOut = new CopyOnWriteArrayList<>();
    public final CopyOnWriteArrayList<UUID> failed = new CopyOnWriteArrayList<>();

    @Override
    public void markExecuting(UUID taskId) {
        executing.add(taskId);
    }

    @Override
    public void markTimedOut(UUID taskId) {
        timedOut.add(taskId);
    }

    @Override
    public void markFailed(UUID taskId) {
        failed.add(taskId);
    }
}
```

- [ ] Write the failing integration test. It boots Spring with both containers, imports the stub port, runs a tiny embedded HTTPS-less localhost webhook receiver via the JDK `HttpServer` (the test sets `hireai.dispatch.allow-insecure-localhost=true` so the client accepts `http://localhost`), publishes a `DispatchMessage`, and awaits `markExecuting`:

```java
package com.hireai.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Boots Spring against real Postgres + RabbitMQ (Testcontainers; auto-skips without Docker).
 * Publishes a DispatchMessage through the real publisher bean and verifies the consumer issues
 * a token, POSTs the webhook (captured by an embedded localhost receiver), and flips the task
 * to EXECUTING through the StubTaskExecutionPort. Plan 3's concrete impl is absent here by design.
 */
@SpringBootTest
@Testcontainers
@Import(StubTaskExecutionPort.class)
@EnabledIf("dockerAvailable")
class DispatchRoundTripIntegrationTest {

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static HttpServer webhookReceiver;
    static final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    static final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeAll
    static void startWebhookReceiver() throws IOException {
        webhookReceiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        webhookReceiver.createContext("/run", exchange -> {
            receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] ok = "{}".getBytes();
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
            exchange.close();
        });
        webhookReceiver.start();
    }

    @AfterAll
    static void stopWebhookReceiver() {
        webhookReceiver.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("hireai.dispatch.allow-insecure-localhost", () -> "true");
        registry.add("hireai.dispatch.token-secret", () -> "integration-secret-integration-secret-32");
    }

    @Autowired TaskDispatchPublisher publisher;
    @Autowired StubTaskExecutionPort executionPort;
    @Autowired ObjectMapper objectMapper;

    private String webhookUrl() {
        int port = webhookReceiver.getAddress().getPort();
        return "http://localhost:" + port + "/run";
    }

    @Test
    void publishedDispatchIsDeliveredAndTaskMarkedExecuting() {
        UUID taskId = UUID.randomUUID();
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise report", "Summarise the attached quarterly report", "summarisation",
                "{\"type\":\"string\"}", "{\"format\":\"JSON\"}",
                "http://localhost/api/agent-callbacks/" + taskId + "/result");
        DispatchMessage message = new DispatchMessage(
                taskId, UUID.randomUUID(), webhookUrl(), "corr-" + taskId, payload);

        publisher.publish(message);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionPort.executing).contains(taskId));

        assertThat(receivedAuthHeader.get()).startsWith("Bearer ");
        assertThat(receivedBody.get()).contains("\"category\":\"summarisation\"");
    }
}
```

- [ ] Run it (expect FAIL on a machine WITH Docker — the config/wiring is incomplete until `application.yml` is saved and beans resolve; on a machine WITHOUT Docker the class is skipped, which is an acceptable interim state — confirm with the full module test that nothing else breaks):

```
mvn -f backend/pom.xml -B test -Dtest=DispatchRoundTripIntegrationTest
```

Expected (with Docker): initially FAIL if any wiring is missing; iterate until PASS. Expected (no Docker): test class SKIPPED (0 run), build green.

- [ ] Note: `awaitility` is already a transitive test dependency via `spring-boot-starter-test`. If the import `org.awaitility.Awaitility` does not resolve, add to `pom.xml` test deps:

```xml
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] Run the FULL backend test suite to confirm no regressions and that non-Docker machines skip cleanly:

```
mvn -f backend/pom.xml -B test
```

Expected: BUILD SUCCESS (integration tests skip without Docker; unit tests for Tasks 2 and 4 pass).

- [ ] Commit:

```
git add backend/src/main/resources/application.yml backend/src/test/java/com/hireai/dispatch/StubTaskExecutionPort.java backend/src/test/java/com/hireai/dispatch/DispatchRoundTripIntegrationTest.java backend/pom.xml
git commit -m "test: RabbitMQ publish-consume integration test + dispatch config"
```

---

## Task 8 — Standalone stub Agent (demo-agent/)

A ~40-line FastAPI process that receives webhook body B, echoes the same Bearer token, and POSTs callback body A to `callbackUrl` after a brief delay. Run instructions and an HTTPS note (dev localhost exception or a tunnel) are included per the spec.

**Files:**
- Create: `demo-agent/app.py`
- Create: `demo-agent/requirements.txt`
- Create: `demo-agent/README.md`

### Steps

- [ ] Create `demo-agent/requirements.txt`:

```
fastapi==0.115.5
uvicorn[standard]==0.32.1
httpx==0.28.1
```

- [ ] Create `demo-agent/app.py`. It accepts the dispatch POST, requires a Bearer token, waits briefly, then POSTs callback body A with the SAME token (wire contract A: `agentStatus`, `resultPayloadJson`, `resultUrl`, `message`):

```python
"""Minimal stub Agent for the HireAI marketplace-spine demo.

Receives a dispatch webhook (wire contract B), echoes the SAME Bearer token, waits
briefly to simulate work, then POSTs a spec-conforming result (wire contract A) to the
platform's callback URL. This is a stand-in for a real third-party Agent.
"""
import asyncio
import json

import httpx
from fastapi import FastAPI, Header, HTTPException, Request

app = FastAPI(title="HireAI demo stub agent")


@app.post("/run")
async def run(request: Request, authorization: str = Header(default="")):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing dispatch token")
    token = authorization.removeprefix("Bearer ")
    body = await request.json()
    task_id = body["taskId"]
    callback_url = body["callbackUrl"]

    # Process asynchronously so the dispatch POST returns immediately (202-style).
    asyncio.create_task(_complete(task_id, callback_url, token))
    return {"accepted": True, "taskId": task_id}


async def _complete(task_id: str, callback_url: str, token: str):
    await asyncio.sleep(2)  # simulate execution
    result_payload = json.dumps({"summary": f"Stub result for task {task_id}"})
    callback_body = {
        "agentStatus": "COMPLETED",
        "resultPayloadJson": result_payload,
        "resultUrl": None,
        "message": "stub agent completed",
    }
    async with httpx.AsyncClient(timeout=10) as client:
        await client.post(
            callback_url,
            headers={"Authorization": f"Bearer {token}"},
            json=callback_body,
        )
```

- [ ] Create `demo-agent/README.md`:

```markdown
# HireAI demo stub Agent

A minimal stand-in for a third-party Agent, used to demo the marketplace spine end-to-end.
It receives a signed dispatch webhook, simulates ~2s of work, and POSTs a spec-conforming
result back to the platform's callback URL using the SAME dispatch token it was given.

## Run

```
cd demo-agent
python -m venv .venv
. .venv/Scripts/activate    # Windows PowerShell: .venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --host 127.0.0.1 --port 9000
```

The agent listens on `http://localhost:9000/run`.

## HTTPS note (Hard Invariant #6)

The platform's `AgentDispatchClient` enforces HTTPS for webhook URLs. Two ways to demo:

1. **Dev-profile localhost exception (simplest).** Set
   `DISPATCH_ALLOW_INSECURE_LOCALHOST=true` on the backend and register the Agent with
   `webhook_url = http://localhost:9000/run`. The signed-token check still applies; only the
   transport check is relaxed for `localhost`.
2. **HTTPS tunnel (faithful).** Expose the stub over HTTPS with a tunnel, e.g.
   `cloudflared tunnel --url http://localhost:9000` or `ngrok http 9000`, and register the
   Agent with the resulting `https://...` URL. No backend flag needed.

## Wire contracts

- **Receives** (body B): `{ taskId, category, title, description, expectedDeliverable, outputSpec, callbackUrl }`
  with headers `Authorization: Bearer <token>`, `X-Correlation-ID: <id>`.
- **Sends** (body A) to `callbackUrl`: `{ agentStatus: "COMPLETED"|"FAILED", resultPayloadJson, resultUrl, message }`
  with header `Authorization: Bearer <same token>`.
```

- [ ] There is no Java test for the stub; sanity-check that the Python parses (optional, requires Python):

```
python -c "import ast; ast.parse(open('demo-agent/app.py').read()); print('ok')"
```

Expected: `ok` (or skip if Python is unavailable on the build machine — the stub is a demo artifact, not part of the Maven build).

- [ ] Commit:

```
git add demo-agent/app.py demo-agent/requirements.txt demo-agent/README.md
git commit -m "feat: standalone FastAPI stub agent for spine demo"
```

---

## Done — Track B deliverables

After Task 8, Track B provides, behind the Plan 0 ports:
- `HmacDispatchTokenService` (issue/verify signed tokens) — implements `DispatchTokenService`.
- `AgentDispatchClient` (HTTPS-only signed webhook POST, dev localhost exception).
- `DispatchQueues` + `RabbitDispatchConfig` (exchange/queue/binding + DLX/DLQ + JSON converter).
- `RabbitTaskDispatchPublisher` (implements `TaskDispatchPublisher`) + `TaskDispatchConsumer` (consume → token → dispatch → `markExecuting`; DLQ → `markFailed`).
- RabbitMQ connection + `hireai.dispatch.*` config in `application.yml`; `spring-boot-starter-amqp` + RabbitMQ Testcontainer in `pom.xml`.
- `demo-agent/` stub.

Synthesis (Routing) and Plan 3 (Task extensions) provide the real `TaskExecutionPort` impl and the routing trigger that calls `TaskDispatchPublisher.publish`. Plan 2 introduces NO dependency on their concrete classes.
```