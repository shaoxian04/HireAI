package com.hireai.task;

import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.wallet.WalletReadAppService;
import com.hireai.application.biz.wallet.WalletWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
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
    @Autowired AgentWriteAppService agentWriteAppService;
    @Autowired AgentRepository agentRepository;
    @Autowired AgentProfileRepository agentProfileRepository;
    @Autowired WalletWriteAppService walletWriteAppService;
    @Autowired WalletReadAppService walletReadAppService;
    @Autowired JdbcTemplate jdbc;

    private UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'CLIENT')", id, id + "@test.local");
        return id;
    }

    private UUID newBuilder() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, role) VALUES (?, ?, 'BUILDER')", id, id + "@test.local");
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
        AgentProfileModel profile = agentProfileRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalStateException("Profile not created by register"));
        agentProfileRepository.save(profile.updateContent("tagline", "desc", "sample", true));

        return agentRepository.findById(agentId)
                .orElseThrow()
                .currentVersionId();
    }

    @Test
    void happyPathTaskQueuedWithAgentSpecAndWalletDebited() {
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

        // outputSpec in the task must match the agent's spec (Invariant #4)
        String outputSpecJson = jdbc.queryForObject(
                "SELECT output_spec FROM tasks WHERE id = ?", String.class, taskId);
        assertThat(outputSpecJson).contains("JSON");

        // Wallet: available 80.00, escrow 20.00
        assertThat(walletReadAppService.getByUserId(client).available()).isEqualTo(Money.of("80.00"));
        assertThat(walletReadAppService.getByUserId(client).escrow()).isEqualTo(Money.of("20.00"));

        // Dispatch published with correct agentVersionId and non-null outputSpec
        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        DispatchMessage dispatched = captor.getValue();
        assertThat(dispatched.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(dispatched.taskId()).isEqualTo(taskId);
        assertThat(dispatched.payload().outputSpecJson()).isNotBlank();
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
