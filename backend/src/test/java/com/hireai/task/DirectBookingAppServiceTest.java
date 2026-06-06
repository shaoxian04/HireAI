package com.hireai.task;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.impl.DirectBookingAppServiceImpl;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DirectBookingAppServiceImpl using plain Mockito. Each test case
 * constructs real AgentModel/AgentVersionModel instances via their public constructors.
 */
class DirectBookingAppServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final AgentProfileRepository agentProfileRepository = mock(AgentProfileRepository.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);

    private final DirectBookingAppServiceImpl service = new DirectBookingAppServiceImpl(
            agentRepository, agentProfileRepository, taskWriteAppService);

    // ---- helpers ----

    private static final OutputSpec AGENT_SPEC = new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    private static final String CATEGORY = "summarisation";
    private static final BigDecimal AGENT_PRICE = new BigDecimal("20.00");

    private AgentVersionModel version(UUID agentId) {
        UUID versionId = UUID.randomUUID();
        return new AgentVersionModel(
                versionId, agentId, 1, AGENT_SPEC, List.of(CATEGORY),
                "https://agent.example.com/hook", 120, Pricing.of(AGENT_PRICE), Instant.now());
    }

    /** Builds an ACTIVE agent with the given version (currentVersionId = version.id()). */
    private AgentModel activeAgent() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel ver = version(agentId);
        return new AgentModel(agentId, UUID.randomUUID(), "Test Agent", AgentStatus.ACTIVE,
                ver.id(), new BigDecimal("50.00"), ver, Instant.now());
    }

    /** Builds a PENDING_VERIFICATION agent. */
    private AgentModel pendingAgent() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel ver = version(agentId);
        return new AgentModel(agentId, UUID.randomUUID(), "Pending Agent",
                AgentStatus.PENDING_VERIFICATION, null, new BigDecimal("50.00"), ver, Instant.now());
    }

    private AgentProfileModel listedProfile(UUID agentId) {
        AgentProfileModel base = AgentProfileModel.createDefault(agentId);
        return base.updateContent("tagline", "desc", "sample", true);
    }

    private AgentProfileModel unlistedProfile(UUID agentId) {
        return AgentProfileModel.createDefault(agentId); // listed = false
    }

    private DirectBookingInfo info(UUID clientId, UUID agentId, String budget) {
        return new DirectBookingInfo(clientId, "Summarise report",
                "Please summarise the report", Money.of(budget), agentId);
    }

    // ---- tests ----

    @Test
    void booksAdoptingAgentSpecAndFirstCategory() {
        AgentModel agent = activeAgent();
        UUID taskId = UUID.randomUUID();
        when(agentRepository.findById(agent.id())).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByAgentId(agent.id()))
                .thenReturn(Optional.of(listedProfile(agent.id())));
        when(taskWriteAppService.submitDirectlyBooked(any(), any())).thenReturn(taskId);

        UUID clientId = UUID.randomUUID();
        UUID result = service.book(info(clientId, agent.id(), "25.00"));

        assertThat(result).isEqualTo(taskId);

        ArgumentCaptor<TaskSubmitInfo> infoCaptor = ArgumentCaptor.forClass(TaskSubmitInfo.class);
        ArgumentCaptor<UUID> versionCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(taskWriteAppService).submitDirectlyBooked(infoCaptor.capture(), versionCaptor.capture());

        TaskSubmitInfo submitted = infoCaptor.getValue();
        assertThat(submitted.clientId()).isEqualTo(clientId);
        assertThat(submitted.outputSpec()).isEqualTo(AGENT_SPEC);
        assertThat(submitted.category()).isEqualTo(CATEGORY);
        assertThat(versionCaptor.getValue()).isEqualTo(agent.currentVersionId());
    }

    @Test
    void rejectsBudgetBelowPrice() {
        AgentModel agent = activeAgent(); // price = 20.00
        when(agentRepository.findById(agent.id())).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByAgentId(agent.id()))
                .thenReturn(Optional.of(listedProfile(agent.id())));

        UUID clientId = UUID.randomUUID();
        assertThatThrownBy(() -> service.book(info(clientId, agent.id(), "5.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.VALIDATION_ERROR));

        verify(taskWriteAppService, never()).submitDirectlyBooked(any(), any());
    }

    @Test
    void rejectsUnlistedActiveAgentAsNotFound() {
        AgentModel agent = activeAgent();
        when(agentRepository.findById(agent.id())).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByAgentId(agent.id()))
                .thenReturn(Optional.of(unlistedProfile(agent.id())));

        UUID clientId = UUID.randomUUID();
        assertThatThrownBy(() -> service.book(info(clientId, agent.id(), "25.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(taskWriteAppService, never()).submitDirectlyBooked(any(), any());
    }

    @Test
    void rejectsMissingAgentAsNotFound() {
        UUID agentId = UUID.randomUUID();
        when(agentRepository.findById(agentId)).thenReturn(Optional.empty());

        UUID clientId = UUID.randomUUID();
        assertThatThrownBy(() -> service.book(info(clientId, agentId, "25.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(taskWriteAppService, never()).submitDirectlyBooked(any(), any());
    }

    @Test
    void rejectsInactiveAgentAsNotFound() {
        AgentModel agent = pendingAgent(); // PENDING_VERIFICATION + listed
        when(agentRepository.findById(agent.id())).thenReturn(Optional.of(agent));
        when(agentProfileRepository.findByAgentId(agent.id()))
                .thenReturn(Optional.of(listedProfile(agent.id())));

        UUID clientId = UUID.randomUUID();
        assertThatThrownBy(() -> service.book(info(clientId, agent.id(), "25.00")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).resultCode())
                        .isEqualTo(ResultCode.NOT_FOUND));

        verify(taskWriteAppService, never()).submitDirectlyBooked(any(), any());
    }
}
