package com.hireai.infrastructure.repository.offering.agent;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration test for the per-agent {@code in_flight}/{@code sample_count}
 * counts and the {@code max_concurrent} column added to the candidate queries in
 * {@link AgentVersionJpaRepository} (spec tests 13-18). Only Postgres is needed — these
 * queries are pure reads with no dispatch/messaging involved, so this follows the lighter
 * Testcontainers-Postgres-only scaffolding already used for other repository-focused ITs
 * (mirrors {@code AgentVersionSupersessionIntegrationTest}'s annotations/dockerAvailable/
 * DynamicPropertySource shape); the JDBC direct-seeding style (users/agents/tasks) matches
 * {@code RoutingIntegrationTest}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class CandidateCountsIntegrationTest {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AgentRepository agentRepository;
    @Autowired JdbcTemplate jdbc;

    /**
     * The Testcontainers Postgres is shared across this class's test methods (static
     * @Container), so seeded agents/tasks persist between tests unless cleared. Reset before
     * each test so per-agent counts never leak across cases (same rationale as
     * RoutingIntegrationTest's clearRoutableAgents).
     */
    @BeforeEach
    void clearRoutableData() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM agent_versions");
        jdbc.update("DELETE FROM agents");
    }

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'CLIENT')", id);
        return id;
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);
        return id;
    }

    /**
     * Seeds one ACTIVE agent with a single ACTIVE version advertising {category} at {price}.
     * When {@code maxConcurrent} is null the column is omitted from the INSERT so the V24
     * DEFAULT 5 backfills it; otherwise the explicit value is stored.
     */
    private UUID[] seedActiveAgent(String category, String price, Integer maxConcurrent) {
        UUID ownerId = newBuilder();
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, current_version_id, reputation_score) " +
                        "VALUES (?, ?, ?, 'ACTIVE', ?, 80.00)",
                agentId, ownerId, "Candidate Agent", versionId);
        if (maxConcurrent == null) {
            jdbc.update("INSERT INTO agent_versions " +
                            "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, " +
                            "max_execution_seconds, price, status) " +
                            "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?, 'ACTIVE')",
                    versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                    "https://agent.example/hook", new BigDecimal(price));
        } else {
            jdbc.update("INSERT INTO agent_versions " +
                            "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, " +
                            "max_execution_seconds, price, status, max_concurrent) " +
                            "VALUES (?, ?, 1, ?::jsonb, ?, ?, 60, ?, 'ACTIVE', ?)",
                    versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                    "https://agent.example/hook", new BigDecimal(price), maxConcurrent);
        }
        return new UUID[]{agentId, versionId};
    }

    /** Inserts a DEPRECATED (superseded) version for an existing agent. */
    private UUID seedDeprecatedVersion(UUID agentId, String category, String price) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_versions " +
                        "(id, agent_id, version_number, output_spec, capability_categories, webhook_url, " +
                        "max_execution_seconds, price, status) " +
                        "VALUES (?, ?, 0, ?::jsonb, ?, ?, 60, ?, 'DEPRECATED')",
                versionId, agentId, "{\"format\":\"JSON\"}", new String[]{category},
                "https://agent.example/old-hook", new BigDecimal(price));
        return versionId;
    }

    private void insertTask(UUID clientId, String status, UUID agentVersionId) {
        jdbc.update("INSERT INTO tasks (id, client_id, title, description, budget, output_spec, status, " +
                        "agent_version_id) VALUES (?, ?, 'Task', 'desc', 10.00, ?::jsonb, ?, ?)",
                UUID.randomUUID(), clientId, "{\"format\":\"JSON\"}", status, agentVersionId);
    }

    // Case 13: 2 QUEUED + 1 EXECUTING (in_flight=3), 1 RESOLVED + 1 FAILED (sample_count=2), 1
    // CANCELLED with agent_version_id NULL (never assigned, must not count either way).
    // maxConcurrent left to the V24 DEFAULT of 5.
    @Test
    void countsInFlightAndSampleAndDefaultsMaxConcurrent() {
        UUID[] agent = seedActiveAgent("summarisation", "10.00", null);
        UUID agentId = agent[0];
        UUID versionId = agent[1];
        UUID client = newClient();

        insertTask(client, "QUEUED", versionId);
        insertTask(client, "QUEUED", versionId);
        insertTask(client, "EXECUTING", versionId);
        insertTask(client, "RESOLVED", versionId);
        insertTask(client, "FAILED", versionId);
        insertTask(client, "CANCELLED", null);

        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                "summarisation", new BigDecimal("30"));

        assertThat(candidates).hasSize(1);
        AgentCandidate candidate = candidates.get(0);
        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.inFlight()).isEqualTo(3L);
        assertThat(candidate.sampleCount()).isEqualTo(2L);
        assertThat(candidate.maxConcurrent()).isEqualTo(5);
    }

    // Case 14: the agent's CURRENT active version has 2 samples; a superseded DEPRECATED old
    // version (same agent) has 2 more RESOLVED tasks pointing at the OLD version id ->
    // sample_count aggregates to 4 (per-agent across all its versions).
    @Test
    void aggregatesSampleCountAcrossSupersededVersions() {
        UUID[] agent = seedActiveAgent("summarisation", "10.00", null);
        UUID agentId = agent[0];
        UUID versionId = agent[1];
        UUID client = newClient();

        insertTask(client, "RESOLVED", versionId);
        insertTask(client, "FAILED", versionId);

        UUID oldVersionId = seedDeprecatedVersion(agentId, "summarisation", "8.00");
        insertTask(client, "RESOLVED", oldVersionId);
        insertTask(client, "RESOLVED", oldVersionId);

        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                "summarisation", new BigDecimal("30"));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).sampleCount()).isEqualTo(4L);
    }

    // Case 15: a second ACTIVE agent with zero tasks -> both counts are exactly 0, never null.
    @Test
    void secondAgentWithNoTasksHasZeroNotNullCounts() {
        seedActiveAgent("summarisation", "10.00", null);
        UUID[] second = seedActiveAgent("summarisation", "12.00", null);
        UUID secondAgentId = second[0];

        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                "summarisation", new BigDecimal("30"));

        assertThat(candidates).hasSize(2);
        AgentCandidate secondCandidate = candidates.stream()
                .filter(c -> c.agentId().equals(secondAgentId))
                .findFirst()
                .orElseThrow();
        assertThat(secondCandidate.inFlight()).isEqualTo(0L);
        assertThat(secondCandidate.sampleCount()).isEqualTo(0L);
    }

    // Case 17: stored category is lowercase ("summarisation", per AgentVersionModel.create);
    // querying with mixed case ("Summarisation") still matches because the repository lowercases
    // the parameter before running the query.
    @Test
    void queryCategoryIsCaseInsensitiveViaRepositoryLowercasing() {
        UUID[] agent = seedActiveAgent("summarisation", "10.00", null);
        UUID agentId = agent[0];

        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                "Summarisation", new BigDecimal("30"));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).agentId()).isEqualTo(agentId);
    }

    // Case 18: findCandidateByVersionId returns the same three new fields as findActiveCandidates.
    @Test
    void findCandidateByVersionIdReturnsNewFields() {
        UUID[] agent = seedActiveAgent("summarisation", "10.00", 7);
        UUID agentId = agent[0];
        UUID versionId = agent[1];
        UUID client = newClient();
        insertTask(client, "QUEUED", versionId);
        insertTask(client, "RESOLVED", versionId);

        Optional<AgentCandidate> found = agentRepository.findCandidateByVersionId(versionId);

        assertThat(found).isPresent();
        AgentCandidate candidate = found.get();
        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.maxConcurrent()).isEqualTo(7);
        assertThat(candidate.inFlight()).isEqualTo(1L);
        assertThat(candidate.sampleCount()).isEqualTo(1L);
    }
}
