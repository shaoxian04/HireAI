package com.hireai.routing;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end marketplace spine: submit a task, let routing match it to a seeded ACTIVE
 * agent, dispatch over RabbitMQ to an in-JVM stub agent, and observe the token-authenticated
 * callback drive the task to RESULT_RECEIVED with a persisted task_results row. Also asserts
 * the no-match path lands on AWAITING_CAPACITY. Boots a real Postgres (Flyway V1-V4) and a
 * real RabbitMQ; auto-skips without Docker. Runs under the 'dev' profile so the localhost
 * stub webhook is allowed (spec invariant #6 local-demo exception); the signed dispatch
 * token is still enforced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("dev")
@EnabledIf("dockerAvailable")
class RoutingIntegrationTest {

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

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        // Allow the localhost stub webhook over http (spec invariant #6 local-demo exception);
        // the signed dispatch token is still enforced. Set here (not via the dev profile) so the
        // test is self-contained and does not depend on a dev block in application.yml.
        registry.add("hireai.dispatch.allow-insecure-localhost", () -> "true");
    }

    @Autowired TaskWriteAppService taskWriteAppService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubAgentController stubAgent;

    /**
     * The Testcontainers Postgres is shared across this class's test methods (static @Container),
     * so seeded ACTIVE agents persist between tests. Clear the routable agent set before each test
     * so the no-match case genuinely sees NO eligible agent (otherwise a 'summarisation' agent
     * seeded by an earlier test would route its task and contaminate the assertion). The
     * append-only ledger/wallet rows are left untouched (each test uses fresh client UUIDs).
     */
    @BeforeEach
    void clearRoutableAgents() {
        jdbc.update("DELETE FROM agent_versions");
        jdbc.update("DELETE FROM agents");
    }

    /**
     * Minimal in-JVM stub Agent. Receives Plan 2's dispatch POST, returns 200 immediately, and
     * then ASYNCHRONOUSLY posts a spec-conforming COMPLETED result back to the callback endpoint
     * with the same Bearer token. The async callback models a real agent: the consumer marks the
     * task EXECUTING right AFTER the dispatch webhook returns (TaskDispatchConsumer dispatches,
     * then markExecuting), so a synchronous in-handler callback would race that transition and hit
     * an illegal QUEUED -> RESULT_RECEIVED. Posting after the handler returns (with a short retry)
     * lets markExecuting land first, so recordResult sees EXECUTING. Lives in the test JVM so the
     * integration test is hermetic (no external demo-agent process).
     */
    @TestConfiguration
    static class StubAgentConfig {
        @Bean
        StubAgentController stubAgentController(
                org.springframework.web.client.RestClient.Builder builder,
                org.springframework.core.env.Environment environment) {
            return new StubAgentController(builder.build(), environment);
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class StubAgentController {
        private final org.springframework.web.client.RestClient http;
        private final org.springframework.core.env.Environment environment;
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newCachedThreadPool();

        StubAgentController(org.springframework.web.client.RestClient http,
                            org.springframework.core.env.Environment environment) {
            this.http = http;
            this.environment = environment;
        }

        @org.springframework.web.bind.annotation.PostMapping("/stub-agent/hook")
        void receive(
                @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization,
                @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Object> body) {
            // Resolve the random server port lazily at request time: by the time the dispatch
            // POST arrives the embedded server has bound and published local.server.port. Reading
            // it during @Bean construction would fail (the port is not yet known at startup).
            int serverPort = environment.getRequiredProperty("local.server.port", Integer.class);
            String taskId = String.valueOf(body.get("taskId"));
            String callbackPath = "http://127.0.0.1:" + serverPort + "/api/agent-callbacks/" + taskId + "/result";
            // Return 200 to the dispatch client now; deliver the result out-of-band so the
            // consumer's markExecuting (QUEUED -> EXECUTING) runs before recordResult.
            executor.submit(() -> postCallbackWithRetry(callbackPath, authorization));
        }

        private void postCallbackWithRetry(String callbackPath, String authorization) {
            RuntimeException last = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    http.post()
                            .uri(callbackPath)
                            .header("Authorization", authorization)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .body(java.util.Map.of(
                                    "agentStatus", "COMPLETED",
                                    "resultPayloadJson", "{\"summary\":\"done\"}",
                                    "resultUrl", "https://results.example/r/1",
                                    "message", "ok"))
                            .retrieve()
                            .toBodilessEntity();
                    return;
                } catch (RuntimeException ex) {
                    // The task may still be QUEUED for a few ms until markExecuting commits; retry.
                    last = ex;
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (last != null) {
                throw last;
            }
        }
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", id, id + "@test.local");
        return id;
    }

    /** Seed one ACTIVE agent with a single version advertising {category} at {price}, webhook -> stub. */
    private UUID seedActiveAgent(String category, String price, String webhookUrl) {
        UUID ownerId = newBuilder();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, 80.00)",
                agentId, ownerId, "Stub Agent", versionId);
        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, max_execution_seconds, price) " +
                        "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?)",
                versionId, agentId, "{\"format\":\"JSON\"}",
                new String[]{category}, webhookUrl, new java.math.BigDecimal(price));
        return versionId;
    }

    private TaskSubmitInfo info(UUID clientId, String category, String budget) {
        return new TaskSubmitInfo(clientId, "Summarise report", "Summarise the attached quarterly report",
                Money.of(budget), new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON summary"),
                category);
    }

    @Test
    void submitRoutesDispatchesAndRecordsResult(@Autowired
            @org.springframework.beans.factory.annotation.Value("${local.server.port}") int serverPort) {
        UUID client = newClient();
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), client);
        String stubWebhook = "http://127.0.0.1:" + serverPort + "/stub-agent/hook";
        UUID versionId = seedActiveAgent("summarisation", "10.00", stubWebhook);

        UUID taskId = taskWriteAppService.submit(info(client, "summarisation", "30.00"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("RESULT_RECEIVED");
        });

        UUID assignedVersion = jdbc.queryForObject(
                "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
        assertThat(assignedVersion).isEqualTo(versionId);

        Integer results = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ? AND agent_status = 'COMPLETED'",
                Integer.class, taskId);
        assertThat(results).isEqualTo(1);
    }

    @Test
    void noMatchingAgentLeavesTaskAwaitingCapacity() {
        UUID client = newClient();
        jdbc.update("INSERT INTO wallets (id, user_id, available_balance, escrow_balance) VALUES (?, ?, 100.00, 0.00)",
                UUID.randomUUID(), client);
        // An ACTIVE agent exists but advertises a different category, so no candidate fits.
        seedActiveAgent("translation", "10.00", "https://unused.example/hook");

        UUID taskId = taskWriteAppService.submit(info(client, "summarisation", "30.00"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
            assertThat(status).isEqualTo("AWAITING_CAPACITY");
        });

        Integer results = jdbc.queryForObject(
                "SELECT count(*) FROM task_results WHERE task_id = ?", Integer.class, taskId);
        assertThat(results).isZero();
    }
}
