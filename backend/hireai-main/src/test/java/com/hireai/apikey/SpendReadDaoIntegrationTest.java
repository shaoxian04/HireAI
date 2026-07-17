package com.hireai.apikey;

import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
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
class SpendReadDaoIntegrationTest {

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

    @Autowired ApiKeyTaskRepository attribution;
    @Autowired SpendReadPort spendRead;
    @Autowired JdbcTemplate jdbc;

    private UUID seedUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@t.local");
        return id;
    }

    private UUID seedKey(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys (id, user_id, key_hash, display_prefix, status, created_at) " +
                "VALUES (?, ?, ?, ?, 'ACTIVE', now())", id, user, id.toString(), "hk_live_x");
        return id;
    }

    /** Seeds a task row in the given status and attributes it to the key at created-at `at`. */
    private void seedAttributedTask(UUID user, UUID keyId, String status, String budget, Instant at) {
        UUID taskId = UUID.randomUUID();
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status, gmt_create) " +
                "VALUES (?, ?, 'T', 'd', ?, '{}'::jsonb, 'cat', ?, now())",
                taskId, user, new BigDecimal(budget), status);
        jdbc.update("INSERT INTO api_key_task (task_id, api_key_id, budget, created_at) VALUES (?, ?, ?, ?)",
                taskId, keyId, new BigDecimal(budget), java.sql.Timestamp.from(at));
    }

    @Test
    void committedForSumsOnlyInFlightTasks() {
        UUID user = seedUser();
        UUID key = seedKey(user);
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        seedAttributedTask(user, key, "QUEUED", "20.00", now);      // in-flight → counts
        seedAttributedTask(user, key, "PENDING_REVIEW", "15.00", now); // in-flight → counts
        seedAttributedTask(user, key, "RESOLVED", "30.00", now);    // released → excluded
        seedAttributedTask(user, key, "CANCELLED", "12.00", now);   // released → excluded

        assertThat(spendRead.committedFor(key)).isEqualByComparingTo("35.00");
    }

    @Test
    void dailySpendForCountsAllOutcomesWithinWindowOnly() {
        UUID user = seedUser();
        UUID key = seedKey(user);
        Instant now = Instant.parse("2026-07-15T10:00:00Z");
        Instant since = now.minusSeconds(24 * 3600);
        seedAttributedTask(user, key, "RESOLVED", "40.00", now.minusSeconds(3600));   // within 24h → counts
        seedAttributedTask(user, key, "CANCELLED", "10.00", now.minusSeconds(7200));  // within 24h, any outcome → counts
        seedAttributedTask(user, key, "RESOLVED", "99.00", now.minusSeconds(90000));  // >24h ago → excluded

        assertThat(spendRead.dailySpendFor(key, since)).isEqualByComparingTo("50.00");
    }
}
