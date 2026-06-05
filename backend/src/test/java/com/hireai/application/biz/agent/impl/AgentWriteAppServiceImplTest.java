package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentWriteAppService;
import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.agent.service.impl.AgentActivateDomainServiceImpl;
import com.hireai.domain.biz.agent.service.impl.AgentRegisterDomainServiceImpl;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
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

    /** In-memory fake of the Agent repository. */
    static class FakeAgentRepository implements AgentRepository {
        final Map<UUID, AgentModel> store = new HashMap<>();

        @Override public AgentModel save(AgentModel agent) { store.put(agent.id(), agent); return agent; }
        @Override public Optional<AgentModel> findById(UUID agentId) { return Optional.ofNullable(store.get(agentId)); }
        @Override public List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query) {
            return store.values().stream().filter(a -> a.ownerId().equals(ownerId)).toList();
        }
        @Override public List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice) {
            return List.of();
        }
    }

    /** Recording event publisher. */
    static class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();
        @Override public void publishEvent(Object event) { events.add(event); }
        @Override public void publishEvent(ApplicationEvent event) { events.add(event); }
    }

    private final FakeAgentRepository repository = new FakeAgentRepository();
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final AgentWriteAppService service = new AgentWriteAppServiceImpl(
            repository, new AgentRegisterDomainServiceImpl(),
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
}
