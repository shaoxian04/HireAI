package com.hireai.support.harness;

import com.hireai.support.agent.FakeAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base for whole-app integration tests: the real Spring Boot app on a real HTTP port, real
 * Postgres (Flyway-migrated) and real RabbitMQ (shared Testcontainers, see
 * {@link HarnessContainers}), plus any number of scriptable {@link FakeAgent}s from
 * {@link #fakeAgent()}. Extend it and drive the app from the outside.
 *
 * <p>Wiring: dispatch may POST to {@code http://localhost} fake Agents (the dev opt-in of Hard
 * Invariant #6; the signed dispatch token is still enforced), and the callback URL in each
 * WorkOrder points at this app's port, so a fake Agent's callback reaches the real endpoint.
 *
 * <p>Isolation: every Spring context gets its own database, RabbitMQ virtual host and port, so
 * subclasses that customise the context ({@code @MockBean}, {@code @Import}) never share rows or
 * queues with another cached context. Test methods in one context do share them: use fresh
 * IDs, and distinct categories when matching must see only your Agents.
 *
 * <p>Dispatch retries use a short schedule ({@code hireai.dispatch.retry-delays} of three 500ms
 * delays); override it with your own {@code @DynamicPropertySource} if a test needs another.
 *
 * <p>Skips the whole class when Docker is unavailable, like the other integration tests. Runs
 * under the {@code test} profile (permissive security, sweepers off).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
public abstract class WholeAppIntegrationTest {

    /**
     * Skip (not fail) the whole subclass without Docker. An assumption in {@code @BeforeAll}
     * rather than {@code @EnabledIf}: JUnit does not inherit {@code @EnabledIf} from a
     * superclass, and this runs before the Spring context (and so any container) is created.
     */
    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(HarnessContainers.dockerAvailable(), "Docker is not available");
    }

    /** Stable across contexts and Testcontainers' defaults; long enough for the HMAC key check. */
    public static final String DISPATCH_TOKEN_SECRET = "harness-dispatch-secret-harness-dispatch-secret";

    private static final AtomicInteger CONTEXTS = new AtomicInteger();

    /** Invoked once per Spring context being built, so each context gets fresh resources. */
    @DynamicPropertySource
    static void harnessProperties(DynamicPropertyRegistry registry) {
        String name = "harness_" + CONTEXTS.incrementAndGet() + "_" + ProcessHandle.current().pid();
        String jdbcUrl = HarnessContainers.newDatabase(name);
        String virtualHost = HarnessContainers.newVirtualHost(name);
        int port = HarnessContainers.freePort();

        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> HarnessContainers.postgres().getUsername());
        registry.add("spring.datasource.password", () -> HarnessContainers.postgres().getPassword());
        registry.add("spring.rabbitmq.host", () -> HarnessContainers.rabbit().getHost());
        registry.add("spring.rabbitmq.port", () -> HarnessContainers.rabbit().getAmqpPort());
        registry.add("spring.rabbitmq.username", () -> HarnessContainers.rabbit().getAdminUsername());
        registry.add("spring.rabbitmq.password", () -> HarnessContainers.rabbit().getAdminPassword());
        registry.add("spring.rabbitmq.virtual-host", () -> virtualHost);
        registry.add("server.port", () -> port);
        registry.add("hireai.platform.public-base-url", () -> "http://localhost:" + port);
        registry.add("hireai.dispatch.allow-insecure-localhost", () -> "true");
        registry.add("hireai.dispatch.token-secret", () -> DISPATCH_TOKEN_SECRET);
        // A short dispatch retry schedule (4 attempts in total) so failed sends dead-letter in
        // well under two seconds instead of the production 5s/10s/15s.
        registry.add("hireai.dispatch.retry-delays", () -> "500ms,500ms,500ms");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private org.springframework.core.env.Environment environment;

    private final List<FakeAgent> fakeAgents = new CopyOnWriteArrayList<>();

    /** A new fake Agent for this test, closed automatically after it. Call as often as needed. */
    protected FakeAgent fakeAgent() {
        FakeAgent agent = FakeAgent.start();
        fakeAgents.add(agent);
        return agent;
    }

    /**
     * Stores {@code agent}'s {@link FakeAgent#signingSecret()} on every Agent row whose version
     * webhook points at it, so its signed callbacks are accepted (#72: the callback must be signed
     * with the assigned Agent's stored secret). Call after seeding the Agent with raw SQL.
     */
    protected void shareSigningSecret(FakeAgent agent) {
        int updated = jdbc.update("UPDATE agents SET signing_secret = ? WHERE id IN "
                + "(SELECT agent_id FROM agent_versions WHERE webhook_url = ?)",
                agent.signingSecret(), agent.webhookUrl());
        if (updated == 0) {
            throw new IllegalStateException("No seeded Agent has webhook " + agent.webhookUrl());
        }
    }

    /** Absolute URL of a path on the app under test, e.g. {@code platformUrl("/api/tasks")}. */
    protected String platformUrl(String path) {
        return "http://localhost:" + environment.getRequiredProperty("server.port") + path;
    }

    @AfterEach
    void closeFakeAgents() {
        fakeAgents.forEach(FakeAgent::close);
        fakeAgents.clear();
    }
}
