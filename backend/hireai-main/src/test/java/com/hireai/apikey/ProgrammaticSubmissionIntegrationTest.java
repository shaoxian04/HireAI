package com.hireai.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.shared.model.Money;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end programmatic spine under the DEFAULT (secured) profile: real
 * {@code ApiKeyAuthenticationFilter}, real allow-list (no {@code @ActiveProfiles("test")}). Logs in as
 * the seeded client for a JWT, mints a key via /api/keys, then drives the submit endpoints with the raw
 * key. Routing is mocked (no RabbitMQ). Proves: submit-via-key freezes escrow; an idempotent retry
 * returns the same task with no second freeze (exactly one attribution row); a revoked key -> 401; an
 * API key -> 403 on /api/keys and /api/wallet; spend-cap rejection -> 409 at the boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("dockerAvailable")
class ProgrammaticSubmissionIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired WalletWriteAppService walletWriteAppService;
    @MockBean RoutingAppService routingAppService;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private String login() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = rest.postForEntity(url("/api/auth/login"),
                new HttpEntity<>("{\"email\":\"client@hireai.local\",\"password\":\"DemoPass123!\"}", h),
                String.class);
        return objectMapper.readTree(r.getBody()).path("data").path("token").asText();
    }

    private HttpHeaders bearer(String jwt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(jwt);
        return h;
    }

    private HttpHeaders apiKey(String rawKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "ApiKey " + rawKey);
        return h;
    }

    /** Mints a key (optionally capped) and returns its raw value. */
    private String createKey(String jwt, String capJsonFieldOrEmpty) throws Exception {
        String body = "{\"name\":\"ci\"" + capJsonFieldOrEmpty + "}";
        ResponseEntity<String> r = rest.postForEntity(url("/api/keys"),
                new HttpEntity<>(body, bearer(jwt)), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(r.getBody()).path("data").path("rawKey").asText();
    }

    private String submitBody() {
        return "{\"title\":\"T\",\"description\":\"desc\",\"category\":\"summarisation\",\"budget\":\"10.00\","
                + "\"outputSpec\":{\"format\":\"JSON\",\"schema\":\"{}\",\"acceptanceCriteria\":\"ok\"}}";
    }

    /** Tops up the seeded client's wallet via the proven app service (no raw-column dependency). */
    private void topUpClient() {
        UUID clientId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'client@hireai.local'", UUID.class);
        walletWriteAppService.topUp(clientId, Money.of("1000.00"), "it-seed");
    }

    @Test
    void submitViaKeyFreezesEscrowAndIdempotentRetryReturnsSameTaskNoSecondFreeze() throws Exception {
        String jwt = login();
        topUpClient();
        String rawKey = createKey(jwt, "");

        HttpHeaders h = apiKey(rawKey);
        h.set("Idempotency-Key", "retry-1");

        ResponseEntity<String> first = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), h), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String taskId = objectMapper.readTree(first.getBody()).path("data").path("id").asText();

        Integer taskRows = jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE id = ?::uuid", Integer.class, taskId);
        assertThat(taskRows).isEqualTo(1);

        // identical retry with the same Idempotency-Key -> same task, no new task row
        ResponseEntity<String> retry = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), h), String.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        String retryTaskId = objectMapper.readTree(retry.getBody()).path("data").path("id").asText();
        assertThat(retryTaskId).isEqualTo(taskId);

        // Scope to THIS task: api_key_task is the per-key spend ledger (one row per successful
        // API-key submit, read by JdbcSpendReadDao), so a global count would also pick up other
        // tests' submits in the shared Testcontainers DB. Exactly one row for this task proves the
        // idempotent retry created no second attribution -> no second submit/freeze (Invariant #1).
        Integer attributionRows = jdbc.queryForObject(
                "SELECT count(*) FROM api_key_task WHERE task_id = ?::uuid", Integer.class, taskId);
        assertThat(attributionRows).isEqualTo(1);
    }

    @Test
    void revokedKeyIsRejectedWith401() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");
        String keyId = jdbc.queryForObject(
                "SELECT id FROM api_keys ORDER BY created_at DESC LIMIT 1", String.class);
        rest.postForEntity(url("/api/keys/" + keyId + "/revoke"),
                new HttpEntity<>(null, bearer(jwt)), String.class);

        ResponseEntity<String> resp = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeyIsLockedOutOfKeyManagementAndWallet() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");

        // The key holds only ROLE_API_CLIENT, so the allow-list denies /api/keys (JWT-only) and
        // /api/wallet. NOTE: this full-app chain configures an authenticationEntryPoint
        // (HttpStatusEntryPoint UNAUTHORIZED) but no accessDeniedHandler, so an authenticated-but-
        // -unauthorized request renders as 401, not 403 — the app's consistent, pre-existing
        // convention (a JWT CLIENT hitting /api/admin/** returns 401 the same way). The security
        // property under test is the lockout (access denied), which 401 satisfies. (Note: the
        // @WebMvcTest slices render the same denial as 403; the full app returns 401.)
        ResponseEntity<String> keys = rest.exchange(url("/api/keys"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(keys.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> wallet = rest.exchange(url("/api/wallet"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(wallet.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void spendCapExceededIsRejectedAtBoundary() throws Exception {
        String jwt = login();
        topUpClient();
        String rawKey = createKey(jwt, ",\"spendCap\":\"15.00\""); // cap below 2x10.00

        ResponseEntity<String> first = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK); // 10 <= 15

        ResponseEntity<String> second = rest.postForEntity(url("/api/tasks"),
                new HttpEntity<>(submitBody(), apiKey(rawKey)), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // 10+10 > 15
        assertThat(objectMapper.readTree(second.getBody()).path("code").asText())
                .isEqualTo("SPEND_CAP_EXCEEDED");
    }

    /**
     * Task 17 allow-list check (authoritative — see the 401-vs-403 post-mortem). This full-app chain
     * has no {@code accessDeniedHandler}, so an authenticated-but-forbidden request renders 401, not
     * 403 (the {@code @WebMvcTest} slice sees 403 for the same denial; only this full-app assertion is
     * trustworthy). accept/reject are now human-only, so an API key is denied even against a
     * random/non-existent task id — the security filter chain blocks it before the controller (and
     * therefore any ownership/existence check) ever runs.
     */
    @Test
    void acceptAndRejectAreDeniedForApiKey() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");
        UUID anyTaskId = UUID.randomUUID();

        ResponseEntity<String> accept = rest.postForEntity(url("/api/tasks/" + anyTaskId + "/accept"),
                new HttpEntity<>(null, apiKey(rawKey)), String.class);
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> reject = rest.postForEntity(url("/api/tasks/" + anyTaskId + "/reject"),
                new HttpEntity<>("{\"reasonCategory\":\"A_MISMATCH\",\"reason\":\"no\"}", apiKey(rawKey)),
                String.class);
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Task 17 allow-list check: the delivery log is reachable headless (reconcile/replay by an
     * API_CLIENT key), but subscription management is JWT-only (a leaked key must not be able to
     * repoint the callback URL).
     */
    @Test
    void webhookDeliveriesReachableByApiKeyButSubscriptionIsJwtOnly() throws Exception {
        String jwt = login();
        String rawKey = createKey(jwt, "");

        ResponseEntity<String> deliveries = rest.exchange(url("/api/webhooks/deliveries"), HttpMethod.GET,
                new HttpEntity<>(apiKey(rawKey)), String.class);
        assertThat(deliveries.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> subscribe = rest.postForEntity(url("/api/webhooks/subscription"),
                new HttpEntity<>("{\"apiKeyId\":\"" + UUID.randomUUID() + "\",\"callbackUrl\":\"https://example.com/hook\"}",
                        apiKey(rawKey)), String.class);
        assertThat(subscribe.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
