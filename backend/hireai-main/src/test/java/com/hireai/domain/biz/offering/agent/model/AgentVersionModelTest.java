package com.hireai.domain.biz.offering.agent.model;

import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentVersionModelTest {

    private OutputSpec spec() {
        return new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON");
    }

    @Test
    void createBuildsVersionOneAndNormalisesCategories() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel v = AgentVersionModel.create(agentId, 1, spec(),
                List.of(" Summarisation ", "TRANSLATION"), "https://agent.example.com/hook",
                120, Pricing.of(new BigDecimal("5.00")), 5);

        assertThat(v.id()).isNotNull();
        assertThat(v.agentId()).isEqualTo(agentId);
        assertThat(v.versionNumber()).isEqualTo(1);
        assertThat(v.capabilityCategories()).containsExactly("summarisation", "translation");
        assertThat(v.webhookUrl()).isEqualTo("https://agent.example.com/hook");
        assertThat(v.maxExecutionSeconds()).isEqualTo(120);
        assertThat(v.pricing().price()).isEqualByComparingTo("5.00");
        assertThat(v.status()).isEqualTo(AgentVersionStatus.ACTIVE);
    }

    @Test
    void rejectsNonHttpsWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "http://agent.example.com/hook", 60, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "  ", 60, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyCategories() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of(), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankCategory() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("ok", "  "), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveMaxExecutionSeconds() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 0, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, null,
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 5))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void createRejectsMaxConcurrentOutOfRange() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 0))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 101))
                .isInstanceOf(DomainException.class);
    }

    // ---- supersededBy (publish-new-version) tests ----

    @Test
    void supersededByCreatesNextActiveVersionCarryingTheContract() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel original = AgentVersionModel.create(agentId, 1, spec(),
                List.of("summarisation"), "https://agent.example.com/hook",
                120, Pricing.of(new BigDecimal("5.00")), 5);

        AgentVersionModel next = original.supersededBy(
                Pricing.of(new BigDecimal("99.50")), 300, List.of(" Translation "));

        // new commercials + incremented version number
        assertThat(next.pricing().price()).isEqualByComparingTo("99.50");
        assertThat(next.maxExecutionSeconds()).isEqualTo(300);
        assertThat(next.capabilityCategories()).containsExactly("translation");
        assertThat(next.versionNumber()).isEqualTo(original.versionNumber() + 1);
        assertThat(next.status()).isEqualTo(AgentVersionStatus.ACTIVE);
        // contract carried over
        assertThat(next.webhookUrl()).isEqualTo(original.webhookUrl());
        assertThat(next.outputSpec()).isEqualTo(original.outputSpec());
        // it is a NEW version (distinct identity), not an in-place edit
        assertThat(next.id()).isNotEqualTo(original.id());
    }

    @Test
    void supersededByCarriesMaxConcurrentOver() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 8);
        AgentVersionModel next = v.supersededBy(Pricing.of(BigDecimal.ONE), 60, List.of("summarisation"));
        assertThat(next.maxConcurrent()).isEqualTo(8);
    }

    @Test
    void supersededByRejectsZeroMaxExecutionSeconds() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 5);
        assertThatThrownBy(() -> v.supersededBy(Pricing.of(BigDecimal.ONE), 0, List.of("summarisation")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void supersededByRejectsEmptyCategories() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE), 5);
        assertThatThrownBy(() -> v.supersededBy(Pricing.of(BigDecimal.ONE), 60, List.of()))
                .isInstanceOf(DomainException.class);
    }

    // ---- assertAffordable (pricing rule) ----

    @Test
    void assertAffordableAcceptsBudgetAtOrAbovePriceAndRejectsBelow() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(new BigDecimal("5.00")), 5);

        assertThatCode(() -> {
            v.assertAffordable(Money.of("5.00")); // equal
            v.assertAffordable(Money.of("10.00")); // above
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> v.assertAffordable(Money.of("4.99")))
                .isInstanceOf(DomainException.class);
    }
}
