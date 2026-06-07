package com.hireai.agent;

import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1–V6.
 * Verifies the AgentProfile persistence slice end-to-end: TEXT[] round-trip, upsert
 * semantics, and gmt_create preservation on second write.
 *
 * Each test creates its own user + agent row via JdbcTemplate so the shared container
 * carries no cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AgentProfileRepositoryIntegrationTest {

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

    @Autowired AgentProfileRepository agentProfileRepository;
    @Autowired JdbcTemplate jdbc;

    /** Insert a BUILDER user and an ACTIVE agent; return the agent id. */
    private UUID seedAgent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')",
                ownerId, ownerId + "@test.local");
        jdbc.update("INSERT INTO agents (id, owner_id, name, status, reputation_score) " +
                        "VALUES (?, ?, 'Test Agent', 'ACTIVE', 50.00)",
                agentId, ownerId);
        return agentId;
    }

    @Test
    void savesAndRoundTripsProfileIncludingGalleryArray() {
        UUID agentId = seedAgent();

        AgentProfileModel profile = AgentProfileModel.createDefault(agentId)
                .updateContent("Fast summaries", "Does summarisation", "{\"sample\":1}", true)
                .withLogo("https://cdn.example.com/logo.png")
                .addGalleryUrl("https://cdn.example.com/g1.png")
                .addGalleryUrl("https://cdn.example.com/g2.png");

        agentProfileRepository.save(profile);

        Optional<AgentProfileModel> loaded = agentProfileRepository.findByAgentId(agentId);
        assertThat(loaded).isPresent();
        AgentProfileModel result = loaded.get();

        assertThat(result.agentId()).isEqualTo(agentId);
        assertThat(result.tagline()).isEqualTo("Fast summaries");
        assertThat(result.description()).isEqualTo("Does summarisation");
        assertThat(result.sampleOutput()).isEqualTo("{\"sample\":1}");
        assertThat(result.logoUrl()).isEqualTo("https://cdn.example.com/logo.png");
        assertThat(result.listed()).isTrue();
        assertThat(result.galleryUrls()).containsExactly(
                "https://cdn.example.com/g1.png",
                "https://cdn.example.com/g2.png");
    }

    @Test
    void saveUpsertsOnSecondWrite() {
        UUID agentId = seedAgent();

        // First save: default (unlisted, no tagline)
        agentProfileRepository.save(AgentProfileModel.createDefault(agentId));

        // Capture gmt_create after first write
        java.sql.Timestamp gmtCreateBefore = jdbc.queryForObject(
                "SELECT gmt_create FROM agent_profiles WHERE agent_id = ?",
                java.sql.Timestamp.class, agentId);

        // Second save: updated content
        agentProfileRepository.save(AgentProfileModel.createDefault(agentId)
                .updateContent("Updated", null, null, true));

        // gmt_create must be unchanged across the upsert
        java.sql.Timestamp gmtCreateAfter = jdbc.queryForObject(
                "SELECT gmt_create FROM agent_profiles WHERE agent_id = ?",
                java.sql.Timestamp.class, agentId);
        assertThat(gmtCreateAfter).isEqualTo(gmtCreateBefore);

        // gmt_modified must be >= gmt_create after the second write
        java.sql.Timestamp gmtModified = jdbc.queryForObject(
                "SELECT gmt_modified FROM agent_profiles WHERE agent_id = ?",
                java.sql.Timestamp.class, agentId);
        assertThat(gmtModified).isAfterOrEqualTo(gmtCreateAfter);

        // Reload and assert only one row + updated tagline
        Optional<AgentProfileModel> loaded = agentProfileRepository.findByAgentId(agentId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().tagline()).isEqualTo("Updated");
        assertThat(loaded.get().listed()).isTrue();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_profiles WHERE agent_id = ?",
                Integer.class, agentId);
        assertThat(count).isEqualTo(1);
    }
}
