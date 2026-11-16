package com.hireai.task;

import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.impl.DirectBookingAppServiceImpl;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.agent.model.AgentVersionModel;
import com.hireai.domain.biz.offering.agent.model.Pricing;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
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
    private final StorefrontRepository agentProfileRepository = mock(StorefrontRepository.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);

    private final DirectBookingAppServiceImpl service = new DirectBookingAppServiceImpl(
            agentRepository, agentProfileRepository, taskWriteAppService,
            currentVersionsOnly(),
            new com.hireai.domain.biz.acceptance.coverage.service.impl.OfferCoverageDomainServiceImpl(),
            java.time.Clock.systemUTC(),
            new com.hireai.domain.biz.task.model.ExecutionGrace(60),
            mock(com.hireai.domain.biz.adjudication.service.ValidationDomainService.class),
            mock(com.hireai.application.biz.acceptance.engagement.EngagementAppService.class),
            mock(com.hireai.application.biz.acceptance.source.SourceSnapshotAppService.class),
            mock(com.hireai.domain.biz.task.sampling.repository.InspectionRepository.class));

    // ---- helpers ----

    /**
     * Templates where every requested version is the current one (no transition): resolveNew keeps the
     * requested Contract under a custom_json-shaped Template, so the Agent's own Offer covers it.
     */
    private static com.hireai.application.biz.acceptance.TemplateReadAppService currentVersionsOnly() {
        com.hireai.application.biz.acceptance.TemplateReadAppService templates =
                mock(com.hireai.application.biz.acceptance.TemplateReadAppService.class);
        when(templates.resolveNew(any(), any())).thenAnswer(call -> {
            com.hireai.domain.biz.acceptance.contract.model.Contract requested = call.getArgument(0);
            com.hireai.domain.biz.acceptance.template.model.TemplateModel template =
                    new com.hireai.domain.biz.acceptance.template.model.TemplateModel(requested.template(), "JSON",
                            null, com.hireai.domain.biz.acceptance.template.enums.OutputShape.JSON, List.of(),
                            List.of(new com.hireai.domain.biz.acceptance.template.model.TemplateParameter("schema",
                                    "JSON Schema", null,
                                    com.hireai.domain.biz.acceptance.template.enums.ParameterComparison.ENUM,
                                    com.hireai.domain.biz.acceptance.template.model.ParameterBounds.openText(5000))),
                            com.hireai.domain.biz.acceptance.template.enums.ExpectationMode.NONE,
                            java.util.Map.of("FULFILLED", BigDecimal.ONE, "PARTIALLY_FULFILLED",
                                    new BigDecimal("0.5"), "NOT_FULFILLED", BigDecimal.ZERO),
                            com.hireai.domain.biz.acceptance.template.enums.TemplateStatus.ACTIVE, Instant.now());
            return new com.hireai.domain.biz.acceptance.template.info.ContractResolution(
                    new com.hireai.domain.biz.acceptance.coverage.info.ContractTerms(template, requested), null);
        });
        return templates;
    }

    private static final OutputSpec AGENT_SPEC = new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    private static final String CATEGORY = "summarisation";
    private static final BigDecimal AGENT_PRICE = new BigDecimal("20.00");

    private AgentVersionModel version(UUID agentId) {
        UUID versionId = UUID.randomUUID();
        return new AgentVersionModel(
                versionId, agentId, 1, AGENT_SPEC, List.of(CATEGORY),
                "https://agent.example.com/hook", 120, 5, Pricing.of(AGENT_PRICE),
                AgentVersionStatus.ACTIVE, Instant.now());
    }

    /** Builds an ACTIVE agent with the given version (currentVersionId = version.id()). */
    private AgentModel activeAgent() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel ver = version(agentId);
        return new AgentModel(agentId, UUID.randomUUID(), "Test Agent", AgentStatus.ACTIVE,
                ver.id(), new BigDecimal("50.00"), ver, Instant.now(), true);
    }

    /** Builds a PENDING_VERIFICATION agent. */
    private AgentModel pendingAgent() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel ver = version(agentId);
        return new AgentModel(agentId, UUID.randomUUID(), "Pending Agent",
                AgentStatus.PENDING_VERIFICATION, null, new BigDecimal("50.00"), ver, Instant.now(), true);
    }

    private StorefrontModel listedProfile(UUID agentId) {
        StorefrontModel base = StorefrontModel.createDefault(agentId);
        return base.updateContent("tagline", "desc", "sample", true);
    }

    private StorefrontModel unlistedProfile(UUID agentId) {
        return StorefrontModel.createDefault(agentId); // listed = false
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
        assertThat(submitted.contract()).isEqualTo(AGENT_SPEC.toContract()); // resolved, then frozen (D36)
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
