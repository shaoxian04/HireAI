package com.hireai.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.application.biz.routing.RoutingAppService;
import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.ledger.wallet.WalletReadAppService;
import com.hireai.application.biz.ledger.wallet.WalletWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration test for direct booking. Boots Spring against a real Postgres (Testcontainers)
 * so Flyway applies V1–V5. Mocks TaskDispatchPublisher (no RabbitMQ needed). Tests:
 * 1. Happy path: task QUEUED, agent_version_id set, outputSpec matches agent's spec, wallet debited.
 * 2. Budget below price: throws VALIDATION_ERROR, no task row, wallet unchanged.
 *
 * NOTE: Test methods are NOT annotated @Transactional. The submit transaction commits when
 * book() returns (the AFTER_COMMIT listener fires synchronously in this context), so the
 * QUEUED state is visible immediately without awaiting.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("dockerAvailable")
class DirectBookingIntegrationTest {

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

    /** Mock the publisher so no RabbitMQ is required. */
    @MockBean TaskDispatchPublisher taskDispatchPublisher;

    @Autowired DirectBookingAppService directBookingAppService;
    @Autowired RoutingAppService routingAppService;
    @Autowired AgentWriteAppService agentWriteAppService;
    @Autowired AgentRepository agentRepository;
    @Autowired StorefrontRepository agentProfileRepository;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

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
     * Seeds an ACTIVE + listed agent. Returns its currentVersionId (set by activate).
     * The profile is updated to listed=true so it is bookable.
     */
    private UUID seedListedActiveAgent(String category, String price) {
        UUID ownerId = newBuilder();
        AgentRegisterInfo info = new AgentRegisterInfo(
                ownerId, "Test Agent",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of(category), "https://agent.example.com/hook", 120,
                new BigDecimal(price));
        UUID agentId = agentWriteAppService.register(info);
        agentWriteAppService.activate(agentId, ownerId);

        // Mark the profile as listed
        StorefrontModel profile = agentProfileRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalStateException("Profile not created by register"));
        agentProfileRepository.save(profile.updateContent("tagline", "desc", "sample", true));

        return agentRepository.findById(agentId)
                .orElseThrow()
                .currentVersionId();
    }

    @Test
    void happyPathTaskQueuedWithAgentSpecAndWalletDebited() throws Exception {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID agentVersionId = seedListedActiveAgent("summarisation", "20.00");
        UUID agentId = agentRepository.findById(
                        jdbc.queryForObject("SELECT agent_id FROM agent_versions WHERE id = ?",
                                UUID.class, agentVersionId))
                .orElseThrow().id();

        DirectBookingInfo bookingInfo = new DirectBookingInfo(
                client, "Summarise report", "Please summarise the report",
                Money.of("20.00"), agentId);

        UUID taskId = directBookingAppService.book(bookingInfo);

        // Task should be QUEUED and pinned to the agent version
        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(status).isEqualTo("QUEUED");

        UUID assignedVersion = jdbc.queryForObject(
                "SELECT agent_version_id FROM tasks WHERE id = ?", UUID.class, taskId);
        assertThat(assignedVersion).isEqualTo(agentVersionId);

        // outputSpec in the task must equal the agent version's spec (Invariant #4).
        // Use Postgres JSONB semantic equality to avoid whitespace/key-order sensitivity.
        Boolean outputSpecMatches = jdbc.queryForObject(
                "SELECT (SELECT output_spec FROM tasks WHERE id = ?) = " +
                "(SELECT output_spec FROM agent_versions WHERE id = ?)",
                Boolean.class, taskId, agentVersionId);
        assertThat(outputSpecMatches).isTrue();

        // Wallet: available 80.00, escrow 20.00
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("80.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.of("20.00"));

        // Dispatch published with correct agentVersionId and non-null outputSpec
        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        DispatchMessage dispatched = captor.getValue();
        assertThat(dispatched.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(dispatched.taskId()).isEqualTo(taskId);

        // Invariant #4: dispatch payload outputSpecJson must come from the TASK's stored spec snapshot,
        // not re-read from the agent version. At booking time both are equal (agent spec was adopted),
        // but we assert against the task row to pin this contract.
        String taskOutputSpec = jdbc.queryForObject(
                "SELECT output_spec::text FROM tasks WHERE id = ?",
                String.class, taskId);
        assertEquals(
                objectMapper.readTree(taskOutputSpec),
                objectMapper.readTree(dispatched.payload().outputSpecJson()),
                "dispatch payload outputSpecJson must be semantically equal to the task's stored output_spec (Invariant #4)");

        // Cross-check: task spec == agent spec at booking time (agent spec was adopted as the task's contract).
        String agentOutputSpec = jdbc.queryForObject(
                "SELECT output_spec::text FROM agent_versions WHERE id = ?",
                String.class, agentVersionId);
        assertEquals(
                objectMapper.readTree(agentOutputSpec),
                objectMapper.readTree(dispatched.payload().outputSpecJson()),
                "at booking time, task output_spec equals the agent version's spec (adoption confirmed)");
    }

    /**
     * Deactivation race: the agent was suspended between booking-validation and dispatch.
     * dispatchDirect must NOT throw; it must mark the task AWAITING_CAPACITY and never publish.
     * Escrow stays frozen (already verified in other tests; not re-asserted here). The race is
     * exercised by seeding a SUBMITTED task row directly (bypassing book() which would 404 on
     * an inactive agent), suspending the agent, then calling dispatchDirect directly.
     */
    @Test
    void deactivatedAgentLandsAwaitingCapacityWithEscrowHeld() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("50.00"), "seed");

        // Register and activate an agent to obtain a valid agent_version_id.
        UUID agentVersionId = seedListedActiveAgent("summarisation", "15.00");
        UUID agentId = agentRepository.findById(
                        jdbc.queryForObject("SELECT agent_id FROM agent_versions WHERE id = ?",
                                UUID.class, agentVersionId))
                .orElseThrow().id();

        // Seed a SUBMITTED task directly so we can simulate the race without going through book()
        // (book() validates ACTIVE status and would 404 on a suspended agent).
        UUID taskId = UUID.randomUUID();
        String outputSpecJson = "{\"format\":\"JSON\",\"schema\":\"{\\\"type\\\":\\\"object\\\"}\",\"acceptanceCriteria\":\"valid JSON\"}";
        jdbc.update(
                "INSERT INTO tasks (id, client_id, title, description, budget, output_spec, category, status, gmt_create) " +
                "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'SUBMITTED', now())",
                taskId, client, "Race task", "Deactivation race test",
                new BigDecimal("15.00"), outputSpecJson, "summarisation");

        // Suspend the agent to simulate the deactivation race.
        jdbc.update("UPDATE agents SET status = 'SUSPENDED' WHERE id = ?", agentId);

        // dispatchDirect must NOT throw, must mark AWAITING_CAPACITY, must NOT publish.
        routingAppService.dispatchDirect(taskId, agentVersionId);

        String status = jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(status).isEqualTo("AWAITING_CAPACITY");

        verify(taskDispatchPublisher, never()).publish(any());
    }

    @Test
    void budgetBelowPriceThrowsNoTaskCreated() {
        UUID client = newClient();
        walletWriteAppService.topUp(client, Money.of("100.00"), "seed");

        UUID agentVersionId = seedListedActiveAgent("summarisation", "20.00");
        UUID agentId = agentRepository.findById(
                        jdbc.queryForObject("SELECT agent_id FROM agent_versions WHERE id = ?",
                                UUID.class, agentVersionId))
                .orElseThrow().id();

        DirectBookingInfo bookingInfo = new DirectBookingInfo(
                client, "Summarise report", "Please summarise",
                Money.of("5.00"), agentId);

        assertThatThrownBy(() -> directBookingAppService.book(bookingInfo))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));

        // No task row should have been created
        Integer taskCount = jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE client_id = ?", Integer.class, client);
        assertThat(taskCount).isZero();

        // Wallet unchanged
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("100.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.ZERO);

        // Dispatch never called
        verify(taskDispatchPublisher, never()).publish(any());
    }
}
