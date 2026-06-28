package com.hireai.application.biz.offering.agent.impl;

import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.offering.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PricingUpdateInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.AgentProfileModel;
import com.hireai.domain.biz.offering.agent.model.AgentVersionModel;
import com.hireai.domain.biz.offering.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.offering.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWriteAppServiceImplTest {

    /** In-memory fake of the Agent repository, including updateCurrentVersion. */
    static class FakeAgentRepository implements AgentRepository {
        final Map<UUID, AgentModel> store = new HashMap<>();
        /** version-id -> persisted version snapshot (mirrors what the JPA impl does). */
        final Map<UUID, AgentVersionModel> versionStore = new HashMap<>();

        @Override public AgentModel save(AgentModel agent) {
            store.put(agent.id(), agent);
            if (agent.currentVersion() != null) {
                versionStore.put(agent.currentVersion().id(), agent.currentVersion());
            }
            return agent;
        }

        @Override public void updateCurrentVersion(AgentVersionModel version) {
            if (!versionStore.containsKey(version.id())) {
                throw new DomainException(ResultCode.NOT_FOUND,
                        "Agent version not found: " + version.id());
            }
            versionStore.put(version.id(), version);
            // rebuild the stored AgentModel so findById reflects the update
            AgentModel existing = store.values().stream()
                    .filter(a -> a.currentVersion() != null
                            && a.currentVersion().id().equals(version.id()))
                    .findFirst()
                    .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                            "Agent for version not found: " + version.id()));
            AgentModel updated = new AgentModel(existing.id(), existing.ownerId(), existing.name(),
                    existing.status(), existing.currentVersionId(), existing.reputationScore(),
                    version, existing.createdAt());
            store.put(updated.id(), updated);
        }

        @Override public Optional<AgentModel> findById(UUID agentId) { return Optional.ofNullable(store.get(agentId)); }
        @Override public List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query) {
            return store.values().stream().filter(a -> a.ownerId().equals(ownerId)).toList();
        }
        @Override public List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice) {
            return List.of();
        }
        @Override public java.util.Optional<AgentCandidate> findCandidateByVersionId(UUID agentVersionId) {
            return java.util.Optional.empty();
        }
        @Override public java.util.Optional<UUID> findOwnerByVersionId(UUID agentVersionId) {
            return store.values().stream()
                    .filter(a -> a.currentVersion() != null
                            && a.currentVersion().id().equals(agentVersionId))
                    .map(AgentModel::ownerId)
                    .findFirst();
        }
    }

    /** In-memory fake of the AgentProfile repository. */
    static class FakeAgentProfileRepository implements AgentProfileRepository {
        final Map<UUID, AgentProfileModel> store = new HashMap<>();

        @Override public AgentProfileModel save(AgentProfileModel profile) {
            store.put(profile.agentId(), profile);
            return profile;
        }
        @Override public Optional<AgentProfileModel> findByAgentId(UUID agentId) {
            return Optional.ofNullable(store.get(agentId));
        }
    }

    /** Recording event publisher. */
    static class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        @Override public void publishEvent(Object event) { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event) { events.add(event); }
    }

    private final FakeAgentRepository repository = new FakeAgentRepository();
    private final FakeAgentProfileRepository profileRepository = new FakeAgentProfileRepository();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AgentWriteAppService service = new AgentWriteAppServiceImpl(
            repository, profileRepository, new AgentRegisterDomainServiceImpl(),
            new AgentActivateDomainServiceImpl(), publisher);

    private AgentRegisterInfo info(UUID ownerId) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), "https://agent.example.com/hook", 120, new BigDecimal("5.00"));
    }

    @Test
    void registerPersistsPendingAgentAndPublishesEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        AgentModel saved = repository.findById(agentId).orElseThrow();
        assertThat(saved.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(saved.ownerId()).isEqualTo(ownerId);
        assertThat(publisher.events).anyMatch(e -> e instanceof AgentRegisteredDomainEvent);
        assertThat(profileRepository.store).containsKey(agentId);
    }

    @Test
    void activateTransitionsToActiveAndPublishesEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        service.activate(agentId, ownerId);

        AgentModel saved = repository.findById(agentId).orElseThrow();
        assertThat(saved.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(saved.currentVersionId()).isNotNull();
        assertThat(publisher.events).anyMatch(e -> e instanceof AgentActivatedDomainEvent);
    }

    @Test
    void activateRejectsNonOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        assertThatThrownBy(() -> service.activate(agentId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void activateRejectsUnknownAgent() {
        assertThatThrownBy(() -> service.activate(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }

    // ---- updatePricing tests ----

    @Test
    void updatePricingPersistsNewCommercialsAndReturnsRefreshedModel() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        PricingUpdateInfo updateInfo = new PricingUpdateInfo(
                new BigDecimal("99.50"), 120, List.of("Translation "));
        AgentModel result = service.updatePricing(agentId, ownerId, updateInfo);

        assertThat(result.currentVersion().pricing().price())
                .isEqualByComparingTo("99.50");
        assertThat(result.currentVersion().maxExecutionSeconds()).isEqualTo(120);
        assertThat(result.currentVersion().capabilityCategories()).containsExactly("translation");
        // identity fields must be unchanged
        AgentModel original = repository.findById(agentId).orElseThrow();
        assertThat(result.currentVersion().id()).isEqualTo(original.currentVersion().id());
        assertThat(result.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(result.currentVersion().outputSpec()).isNotNull();
    }

    @Test
    void updatePricingRejectsForeignOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));

        assertThatThrownBy(() -> service.updatePricing(agentId, UUID.randomUUID(),
                new PricingUpdateInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updatePricingRejectsUnknownAgent() {
        assertThatThrownBy(() -> service.updatePricing(UUID.randomUUID(), UUID.randomUUID(),
                new PricingUpdateInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }
}
