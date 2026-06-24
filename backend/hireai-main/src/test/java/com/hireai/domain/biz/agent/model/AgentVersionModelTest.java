package com.hireai.domain.biz.agent.model;

import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
                120, Pricing.of(new BigDecimal("5.00")));

        assertThat(v.id()).isNotNull();
        assertThat(v.agentId()).isEqualTo(agentId);
        assertThat(v.versionNumber()).isEqualTo(1);
        assertThat(v.capabilityCategories()).containsExactly("summarisation", "translation");
        assertThat(v.webhookUrl()).isEqualTo("https://agent.example.com/hook");
        assertThat(v.maxExecutionSeconds()).isEqualTo(120);
        assertThat(v.pricing().price()).isEqualByComparingTo("5.00");
    }

    @Test
    void rejectsNonHttpsWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "http://agent.example.com/hook", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankWebhook() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "  ", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsEmptyCategories() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of(), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsBlankCategory() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("ok", "  "), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNonPositiveMaxExecutionSeconds() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 0, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsNullOutputSpec() {
        assertThatThrownBy(() -> AgentVersionModel.create(UUID.randomUUID(), 1, null,
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE)))
                .isInstanceOf(DomainException.class);
    }

    // ---- updateCommercials tests ----

    @Test
    void updateCommercialsReturnsCopyWithNewPriceMaxExecAndCategories() {
        UUID agentId = UUID.randomUUID();
        AgentVersionModel original = AgentVersionModel.create(agentId, 1, spec(),
                List.of("summarisation"), "https://agent.example.com/hook",
                120, Pricing.of(new BigDecimal("5.00")));

        AgentVersionModel updated = original.updateCommercials(
                Pricing.of(new BigDecimal("99.50")), 300, List.of(" Translation "));

        // new commercials
        assertThat(updated.pricing().price()).isEqualByComparingTo("99.50");
        assertThat(updated.maxExecutionSeconds()).isEqualTo(300);
        assertThat(updated.capabilityCategories()).containsExactly("translation");

        // identity fields preserved
        assertThat(updated.id()).isEqualTo(original.id());
        assertThat(updated.versionNumber()).isEqualTo(original.versionNumber());
        assertThat(updated.webhookUrl()).isEqualTo(original.webhookUrl());
        assertThat(updated.outputSpec()).isEqualTo(original.outputSpec());
        assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    }

    @Test
    void updateCommercialsRejectsZeroMaxExecutionSeconds() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThatThrownBy(() -> v.updateCommercials(Pricing.of(BigDecimal.ONE), 0, List.of("summarisation")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void updateCommercialsRejectsEmptyCategories() {
        AgentVersionModel v = AgentVersionModel.create(UUID.randomUUID(), 1, spec(),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
        assertThatThrownBy(() -> v.updateCommercials(Pricing.of(BigDecimal.ONE), 60, List.of()))
                .isInstanceOf(DomainException.class);
    }
}
