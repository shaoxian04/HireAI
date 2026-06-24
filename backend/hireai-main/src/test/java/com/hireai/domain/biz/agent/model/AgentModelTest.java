package com.hireai.domain.biz.agent.model;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    }

    private AgentModel registered(UUID ownerId) {
        return AgentModel.register(ownerId, "Summariser Bot", spec(),
                List.of("summarisation"), "https://agent.example.com/hook", 120,
                Pricing.of(new BigDecimal("5.00")));
    }

    @Test
    void registerBuildsPendingAgentWithVersionOne() {
        UUID ownerId = UUID.randomUUID();
        AgentModel agent = registered(ownerId);

        assertThat(agent.id()).isNotNull();
        assertThat(agent.ownerId()).isEqualTo(ownerId);
        assertThat(agent.name()).isEqualTo("Summariser Bot");
        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersionId()).isNull();
        assertThat(agent.reputationScore()).isEqualByComparingTo("50.00");
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().agentId()).isEqualTo(agent.id());
    }

    @Test
    void registerTrimsName() {
        AgentModel agent = AgentModel.register(UUID.randomUUID(), "  Bot  ", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThat(agent.name()).isEqualTo("Bot");
    }

    @Test
    void registerRejectsBlankName() {
        assertThatThrownBy(() -> AgentModel.register(UUID.randomUUID(), "  ", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void registerRejectsNullOwner() {
        assertThatThrownBy(() -> AgentModel.register(null, "Bot", spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void activateTransitionsToActiveAndSetsCurrentVersionImmutably() {
        AgentModel pending = registered(UUID.randomUUID());

        AgentModel active = pending.activate();

        assertThat(active.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(active.currentVersionId()).isEqualTo(pending.currentVersion().id());
        assertThat(active.id()).isEqualTo(pending.id());
        // original is unchanged (immutability)
        assertThat(pending.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(pending.currentVersionId()).isNull();
    }

    @Test
    void activateRejectsAlreadyActiveAgent() {
        AgentModel active = registered(UUID.randomUUID()).activate();
        assertThatThrownBy(active::activate).isInstanceOf(DomainException.class);
    }

    @Test
    void isActiveOnlyWhenStatusActive() {
        AgentModel pending = registered(UUID.randomUUID());
        assertThat(pending.isActive()).isFalse();
        assertThat(pending.activate().isActive()).isTrue();
    }
}
