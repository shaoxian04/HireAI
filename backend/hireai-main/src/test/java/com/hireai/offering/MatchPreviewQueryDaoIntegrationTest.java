package com.hireai.offering;

import com.hireai.application.port.query.MatchPreviewQueryPort;
import com.hireai.application.port.query.MatchPreviewQueryPort.ShortlistCandidateRow;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Testcontainers IT for the bookable-candidate query (spec §6.1; test plan items 8-11). */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class MatchPreviewQueryDaoIntegrationTest {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MatchPreviewQueryPort queryPort;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM agent_versions");
        jdbc.update("DELETE FROM agent_profiles");
        jdbc.update("DELETE FROM agents");
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);
        return id;
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    /** Seeds one agent (status) + current version ({category}@{price}) + a profile ({isListed}). */
    private UUID[] seedAgent(String status, boolean isListed, String category, String price) {
        UUID ownerId = newBuilder();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, ?, ?, 80.00)",
                agentId, ownerId, "Agent " + price, status, versionId);
        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, " +
                        "max_execution_seconds, price, status) " +
                        "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?, 'ACTIVE')",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                "https://agent.example/hook", new BigDecimal(price));
        jdbc.update("INSERT INTO agent_profiles (agent_id, tagline, is_listed, is_featured) " +
                        "VALUES (?, 'T', ?, false)",
                agentId, isListed);
        return new UUID[]{agentId, versionId};
    }

    private void insertTask(UUID clientId, String status, UUID agentVersionId) {
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, " +
                        "agent_version_id) VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?, ?)",
                UUID.randomUUID(), clientId, "{\"format\":\"JSON\"}", status, agentVersionId);
    }

    @Test
    void returnsOnlyActiveListedAgentsForTheCategory() {
        UUID[] wanted = seedAgent("ACTIVE", true, "summarisation", "10.00");
        seedAgent("ACTIVE", false, "summarisation", "11.00");   // unlisted -> excluded
        seedAgent("SUSPENDED", true, "summarisation", "12.00");  // inactive -> excluded
        seedAgent("ACTIVE", true, "translation", "13.00");       // other category -> excluded

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).agentId()).isEqualTo(wanted[0]);
        assertThat(rows.get(0).outputFormat()).isEqualTo("JSON");
    }

    @Test
    void hasNoPriceFilterSoBothInAndOverBudgetComeBack() {
        seedAgent("ACTIVE", true, "summarisation", "10.00");
        seedAgent("ACTIVE", true, "summarisation", "50.00");

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).extracting(ShortlistCandidateRow::price)
                .anySatisfy(p -> assertThat(p).isEqualByComparingTo("10.00"))
                .anySatisfy(p -> assertThat(p).isEqualByComparingTo("50.00"));
    }

    @Test
    void computesPerAgentInFlightAndSampleCounts() {
        UUID[] agent = seedAgent("ACTIVE", true, "summarisation", "10.00");
        UUID client = newClient();
        insertTask(client, "QUEUED", agent[1]);
        insertTask(client, "EXECUTING", agent[1]);
        insertTask(client, "RESOLVED", agent[1]);

        List<ShortlistCandidateRow> rows = queryPort.findBookableCandidates("summarisation");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).inFlight()).isEqualTo(2L);
        assertThat(rows.get(0).sampleCount()).isEqualTo(1L);
    }

    @Test
    void categoryIsCaseInsensitive() {
        seedAgent("ACTIVE", true, "summarisation", "10.00");
        assertThat(queryPort.findBookableCandidates("Summarisation")).hasSize(1);
    }
}
