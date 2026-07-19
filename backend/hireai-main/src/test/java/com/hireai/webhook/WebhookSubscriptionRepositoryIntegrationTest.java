package com.hireai.webhook;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.webhook.model.WebhookSubscriptionModel;
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
 * creating the client_webhook_subscriptions table. Verifies that the WebhookSubscriptionModel
 * aggregate round-trips through the repository (save → findActiveByApiKeyId).
 *
 * Skipped (not failed) when no Docker daemon is reachable — CI-gated.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class WebhookSubscriptionRepositoryIntegrationTest {

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

    @Autowired WebhookSubscriptionRepository repo;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired JdbcTemplate jdbc;

    private UUID seedKeyOwnedBy(UUID owner) {
        // Seed a users row if not present, then create an api_keys row via ApiKeyModel
        // so the FK references are valid.
        if (owner == null) {
            owner = UUID.randomUUID();
        }
        try {
            jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", owner, owner + "@test.local");
        } catch (Exception e) {
            // User might already exist; continue
        }

        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        ApiKeyModel key = ApiKeyModel.issue(owner, "sub-test-hash", "hk_live_sub_test", "webhook-test",
                null, null, now);
        apiKeys.save(key);
        return key.id();
    }

    @Test void savesAndFindsActiveByApiKey() {
        UUID owner = UUID.randomUUID();
        UUID keyId = seedKeyOwnedBy(owner);
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        repo.save(WebhookSubscriptionModel.create(id, keyId, owner, "https://client.example.com/cb", "whsec_abc", now));

        var found = repo.findActiveByApiKeyId(keyId);
        assertThat(found).isPresent();
        assertThat(found.get().callbackUrl()).isEqualTo("https://client.example.com/cb");
        assertThat(found.get().signingSecret()).isEqualTo("whsec_abc");
        assertThat(found.get().active()).isTrue();
    }

    @Test void deactivatedIsNotFoundAsActive() {
        UUID owner = UUID.randomUUID();
        UUID keyId = seedKeyOwnedBy(owner);
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        var sub = WebhookSubscriptionModel.create(UUID.randomUUID(), keyId, owner, "https://a/b", "s", now);
        repo.save(sub);
        repo.save(sub.deactivate(now.plusSeconds(1)));
        assertThat(repo.findActiveByApiKeyId(keyId)).isEmpty();
    }
}
