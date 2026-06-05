package com.hireai.agent;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots Spring against a real Postgres (Testcontainers) so Flyway applies V1+V2+V3.
 * Verifies the Agent registration slice end-to-end: register (PENDING_VERIFICATION + v1),
 * activate (-> ACTIVE + current_version_id), owner-scoped get/list, the JSONB + TEXT[]
 * round-trip, and the routing candidate read (category overlap, budget filter, reputation
 * tie-break ordering). Each test creates its own owner so the shared container carries no
 * cross-test state.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class AgentRegistrationIntegrationTest {

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
    @Autowired AgentRepository agentRepository;
    @Autowired JdbcTemplate jdbc;

    private UUID newOwner() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", id, id + "@test.local");
        return id;
    }

    private AgentRegisterInfo info(UUID ownerId, String category, String price) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), "https://agent.example.com/hook", 120, new BigDecimal(price));
    }

    @Test
    void registerPersistsPendingAgentWithVersionOne() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));

        AgentModel agent = agentReadAppService.getForOwner(agentId, owner);
        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersionId()).isNull();
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().capabilityCategories()).containsExactly("summarisation");
        assertThat(agent.currentVersion().outputSpec().format()).isEqualTo(OutputFormat.JSON);
    }

    @Test
    void activateTransitionsToActiveAndSetsCurrentVersion() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "translation", "3.00"));

        agentWriteAppService.activate(agentId, owner);

        AgentModel agent = agentReadAppService.getForOwner(agentId, owner);
        assertThat(agent.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(agent.currentVersionId()).isEqualTo(agent.currentVersion().id());
    }

    @Test
    void getRejectsNonOwner() {
        UUID owner = newOwner();
        UUID agentId = agentWriteAppService.register(info(owner, "summarisation", "5.00"));

        assertThatThrownBy(() -> agentReadAppService.getForOwner(agentId, newOwner()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void listReturnsOnlyOwnedAgents() {
        UUID owner = newOwner();
        agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        agentWriteAppService.register(info(owner, "translation", "3.00"));
        agentWriteAppService.register(info(newOwner(), "summarisation", "5.00"));

        List<AgentModel> mine = agentReadAppService.listForOwner(owner, AgentQuery.firstPage());
        assertThat(mine).hasSize(2);
        assertThat(mine).allMatch(a -> a.ownerId().equals(owner));
    }

    @Test
    void findActiveCandidatesReturnsOnlyActiveInCategoryWithinBudgetOrderedByReputation() {
        // A cheaper active agent and a pricier active agent, both in 'summarisation';
        // plus one still PENDING (must be excluded) and one out-of-category (excluded).
        UUID owner = newOwner();
        UUID cheap = agentWriteAppService.register(info(owner, "summarisation", "5.00"));
        UUID pricey = agentWriteAppService.register(info(owner, "summarisation", "9.00"));
        UUID pending = agentWriteAppService.register(info(owner, "summarisation", "1.00"));
        UUID other = agentWriteAppService.register(info(owner, "translation", "1.00"));
        agentWriteAppService.activate(cheap, owner);
        agentWriteAppService.activate(pricey, owner);
        agentWriteAppService.activate(other, owner);
        // give 'pricey' the higher reputation so it sorts first
        jdbc.update("UPDATE agents SET reputation_score = 80.00 WHERE id = ?", pricey);
        jdbc.update("UPDATE agents SET reputation_score = 60.00 WHERE id = ?", cheap);

        List<AgentCandidate> candidates =
                agentRepository.findActiveCandidates("summarisation", new BigDecimal("10.00"));

        assertThat(candidates).extracting(AgentCandidate::agentId).containsExactly(pricey, cheap);
        assertThat(candidates).noneMatch(c -> c.agentId().equals(pending));
        assertThat(candidates).noneMatch(c -> c.agentId().equals(other));

        // budget filter: max 6.00 excludes the 9.00 'pricey' agent
        List<AgentCandidate> withinBudget =
                agentRepository.findActiveCandidates("summarisation", new BigDecimal("6.00"));
        assertThat(withinBudget).extracting(AgentCandidate::agentId).containsExactly(cheap);
    }
}
