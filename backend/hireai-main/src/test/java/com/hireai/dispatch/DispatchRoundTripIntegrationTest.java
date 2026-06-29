package com.hireai.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
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
