package com.hireai.offering;

import com.hireai.application.biz.offering.agent.AgentReadAppService;
import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PublishVersionInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
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

/**
 * Testcontainers integration test for the publish-new-version / supersession use case.
 * Exercises three versions (v1 via register, then publish v2 and v3) to prove:
 * - The partial-unique index {@code uq_agent_versions_one_active} holds across repeated
 *   supersession (never two ACTIVE rows for one agent).
 * - History accumulates: all prior versions are retained as DEPRECATED.
 * - {@code agents.current_version_id} always tracks the ACTIVE version.
 *
 * This test is CI-gated (Docker unavailable locally) — validated in CI.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AgentVersionSupersessionIntegrationTest {

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

    @Autowired AgentWriteAppService agentWriteAppService;
    @Autowired AgentReadAppService agentReadAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newOwner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email) VALUES (?, ?)", id, id + "@test.local");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'BUILDER')", id);
        return id;
    }

    private AgentRegisterInfo info(UUID ownerId, String category, String price) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), "https://agent.example.com/hook", 120, new BigDecimal(price));
    }

    @Test
    void publishNewVersionSupersedesPriorActiveAndKeepsHistory() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        agentWriteAppService.activate(agentId, owner);

        AgentModel v1 = agentReadAppService.getForOwner(agentId, owner);
        UUID v1Id = v1.currentVersion().id();
        assertThat(v1.currentVersion().versionNumber()).isEqualTo(1);

        agentWriteAppService.publishNewVersion(agentId, owner,
                new PublishVersionInfo(new BigDecimal("12.00"), 200, List.of("summarisation")));
        agentWriteAppService.publishNewVersion(agentId, owner,
                new PublishVersionInfo(new BigDecimal("15.00"), 250, List.of("translation")));

        AgentModel current = agentReadAppService.getForOwner(agentId, owner);
        assertThat(current.currentVersion().versionNumber()).isEqualTo(3);
        assertThat(current.currentVersion().pricing().price()).isEqualByComparingTo("15.00");
        assertThat(current.currentVersion().capabilityCategories()).containsExactly("translation");
        assertThat(current.currentVersion().id()).isNotEqualTo(v1Id);
        // agents.current_version_id tracks the ACTIVE version
        assertThat(current.currentVersionId()).isEqualTo(current.currentVersion().id());

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_versions WHERE agent_id = ? AND status = 'ACTIVE'",
                Integer.class, agentId);
        assertThat(activeCount).isEqualTo(1);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_versions WHERE agent_id = ?", Integer.class, agentId);
        assertThat(total).isEqualTo(3);
        String v1Status = jdbc.queryForObject(
                "SELECT status FROM agent_versions WHERE id = ?", String.class, v1Id);
        assertThat(v1Status).isEqualTo("DEPRECATED");
    }
}
