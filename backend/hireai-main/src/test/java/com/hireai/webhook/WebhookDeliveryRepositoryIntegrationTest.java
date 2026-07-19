package com.hireai.webhook;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.enums.WebhookEventType;
import com.hireai.domain.biz.webhook.model.WebhookDeliveryModel;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
import com.hireai.domain.biz.webhook.repository.WebhookDeliveryRepository;
import com.hireai.domain.biz.webhook.repository.WebhookSubscriptionRepository;
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

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V26,
 * creating the webhook_deliveries table. Verifies that the WebhookDeliveryModel aggregate
 * round-trips through the repository (save / findDueIds / claimForUpdate / findForOwner),
 * and specifically that the native FOR UPDATE SKIP LOCKED claim query only returns a row
 * while it is still PENDING and due (mirrors WebhookSubscriptionRepositoryIntegrationTest's
 * annotations/dockerAvailable/DynamicPropertySource shape and JDBC direct-seeding style).
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class WebhookDeliveryRepositoryIntegrationTest {

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

    @Autowired WebhookDeliveryRepository repo;
    @Autowired WebhookSubscriptionRepository subscriptions;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired JdbcTemplate jdbc;

    private UUID seedOwner() {
        UUID owner = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", owner, owner + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", owner);
        return owner;
    }

    private UUID seedTask(UUID clientId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status) " +
                        "VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, 'SUBMITTED')",
                id, clientId, "{\"format\":\"JSON\"}");
        return id;
    }

    private UUID seedSubscription(UUID owner, Instant now) {
        UUID keyId = UUID.randomUUID();
        ApiKeyModel key = ApiKeyModel.issue(owner, "delivery-test-hash-" + keyId, "hk_live_delivery_test",
                "webhook-delivery-test", null, null, now);
        apiKeys.save(key);
        UUID subId = UUID.randomUUID();
        subscriptions.save(WebhookSubscriptionModel.create(subId, key.id(), owner,
                "https://client.example.com/cb", "whsec_delivery_test", now));
        return subId;
    }

    @Test void savesFindsDueClaimsAndFiltersByOwner() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        UUID owner = seedOwner();
        UUID task = seedTask(owner);
        UUID sub = seedSubscription(owner, now);

        var d = WebhookDeliveryModel.enqueue(UUID.randomUUID(), task, owner, sub,
                WebhookEventType.TASK_COMPLETED, "{\"a\":1}", "https://x/y", now);
        repo.save(d);

        assertThat(repo.findDueIds(now.plusSeconds(1), 50)).contains(d.id());
        assertThat(repo.findDueIds(now.minusSeconds(1), 50)).doesNotContain(d.id()); // not yet due

        var claimed = repo.claimForUpdate(d.id(), now.plusSeconds(1));
        assertThat(claimed).isPresent();
        repo.save(claimed.get().markDelivered(now.plusSeconds(2)));
        assertThat(repo.claimForUpdate(d.id(), now.plusSeconds(3))).isEmpty(); // DELIVERED no longer claimable

        var byOwner = repo.findForOwner(owner, now.minusSeconds(60), null, null);
        assertThat(byOwner).extracting(WebhookDeliveryModel::id).contains(d.id());
        assertThat(repo.findForOwner(owner, now.minusSeconds(60), "DELIVERED", task)).isNotEmpty();
        assertThat(repo.findForOwner(owner, now.minusSeconds(60), "DEAD", null)).isEmpty();
    }
}
