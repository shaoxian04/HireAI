package com.hireai.application.biz.offering.agent.impl;

import com.hireai.application.biz.offering.agent.AgentWriteAppService;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.offering.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.info.PublishVersionInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.agent.model.AgentVersionModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.offering.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentDeactivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentReactivateDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.offering.agent.service.impl.AgentSuspendDomainServiceImpl;
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

    /** In-memory fake of the Agent repository, including publishNewVersion. */
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

        @Override public void publishNewVersion(AgentModel agent) {
            store.put(agent.id(), agent);
            if (agent.currentVersion() != null) {
                versionStore.put(agent.currentVersion().id(), agent.currentVersion());
            }
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
    static class FakeStorefrontRepository implements StorefrontRepository {
        final Map<UUID, StorefrontModel> store = new HashMap<>();

        @Override public StorefrontModel save(StorefrontModel profile) {
            store.put(profile.agentId(), profile);
            return profile;
        }
        @Override public Optional<StorefrontModel> findByAgentId(UUID agentId) {
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
    private final FakeStorefrontRepository profileRepository = new FakeStorefrontRepository();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AgentWriteAppService service = new AgentWriteAppServiceImpl(
            repository, profileRepository, new AgentRegisterDomainServiceImpl(),
            new AgentActivateDomainServiceImpl(), new AgentSuspendDomainServiceImpl(),
            new AgentReactivateDomainServiceImpl(), new AgentDeactivateDomainServiceImpl(), publisher);

    private AgentRegisterInfo info(UUID ownerId) {
        return new AgentRegisterInfo(ownerId, "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), "https://agent.example.com/hook", 120, new BigDecimal("5.00"), 5);
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

    // ---- publishNewVersion tests ----

    @Test
    void publishNewVersionCreatesIncrementedActiveVersionAndReturnsRefreshedModel() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        UUID v1Id = repository.findById(agentId).orElseThrow().currentVersion().id();

        AgentModel result = service.publishNewVersion(agentId, ownerId,
                new PublishVersionInfo(new BigDecimal("99.50"), 120, List.of("Translation ")));

        assertThat(result.currentVersion().versionNumber()).isEqualTo(2);
        assertThat(result.currentVersion().pricing().price()).isEqualByComparingTo("99.50");
        assertThat(result.currentVersion().capabilityCategories()).containsExactly("translation");
        assertThat(result.currentVersion().status())
                .isEqualTo(com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus.ACTIVE);
        assertThat(result.currentVersion().id()).isNotEqualTo(v1Id);
        assertThat(result.currentVersion().outputSpec()).isNotNull();
    }

    @Test
    void publishNewVersionRejectsForeignOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        assertThatThrownBy(() -> service.publishNewVersion(agentId, UUID.randomUUID(),
                new PublishVersionInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void publishNewVersionRejectsUnknownAgent() {
        assertThatThrownBy(() -> service.publishNewVersion(UUID.randomUUID(), UUID.randomUUID(),
                new PublishVersionInfo(new BigDecimal("10.00"), 60, List.of("summarisation"))))
                .isInstanceOf(DomainException.class);
    }

    // ---- suspend / reactivate / deactivate tests ----

    @Test
    void suspendReactivateDeactivateTransitionOwnedAgent() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        service.activate(agentId, ownerId);

        service.suspend(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.SUSPENDED);

        service.reactivate(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.ACTIVE);

        service.deactivate(agentId, ownerId);
        assertThat(repository.findById(agentId).orElseThrow().status())
                .isEqualTo(AgentStatus.DEACTIVATED);
    }

    @Test
    void suspendRejectsForeignOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = service.register(info(ownerId));
        service.activate(agentId, ownerId);
        assertThatThrownBy(() -> service.suspend(agentId, UUID.randomUUID()))
                .isInstanceOf(DomainException.class);
    }
}
