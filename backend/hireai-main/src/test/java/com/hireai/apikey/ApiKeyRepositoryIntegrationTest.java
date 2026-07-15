package com.hireai.apikey;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class ApiKeyRepositoryIntegrationTest {

    static boolean dockerAvailable() {
        try { return DockerClientFactory.instance().isDockerAvailable(); }
        catch (Throwable t) { return false; }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApiKeyRepository repository;
    @Autowired JdbcTemplate jdbc;

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        return id;
    }

    @Test
    void savedActiveKeyIsFoundByHashAndRevokedIsNot() {
        UUID user = newUser();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel key = ApiKeyModel.issue(user, "abc-hash", "hk_live_a1b2c3", "ci-bot",
                new BigDecimal("100.00"), null, now);
        repository.save(key);

        assertThat(repository.findActiveByHash("abc-hash")).isPresent();
        assertThat(repository.findById(key.id())).isPresent();
        assertThat(repository.findByUserId(user)).hasSize(1);

        repository.save(key.revoke(now.plusSeconds(60)));
        assertThat(repository.findActiveByHash("abc-hash")).isEmpty(); // revoked → not active
        assertThat(repository.findById(key.id())).get()
                .extracting(ApiKeyModel::status).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void touchLastUsedUpdatesTimestamp() {
        UUID user = newUser();
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        ApiKeyModel key = ApiKeyModel.issue(user, "h2", "hk_live_zzz111", "bot", null, null, now);
        repository.save(key);
        repository.touchLastUsed(key.id(), now.plusSeconds(120));
        assertThat(repository.findById(key.id())).get()
                .extracting(ApiKeyModel::lastUsedAt).isEqualTo(now.plusSeconds(120));
    }
}
