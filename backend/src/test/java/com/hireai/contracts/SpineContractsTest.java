package com.hireai.contracts;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SpineContractsTest {

    @Test
    void agentCandidateExposesAllAccessors() {
        UUID agentId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        AgentCandidate candidate = new AgentCandidate(
                agentId, agentVersionId, List.of("SUMMARISATION", "TRANSLATION"),
                new BigDecimal("12.50"), "https://agent.example.com/webhook", 120,
                new BigDecimal("87.25"));

        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(candidate.capabilityCategories()).containsExactly("SUMMARISATION", "TRANSLATION");
        assertThat(candidate.price()).isEqualByComparingTo("12.50");
        assertThat(candidate.webhookUrl()).isEqualTo("https://agent.example.com/webhook");
        assertThat(candidate.maxExecutionSeconds()).isEqualTo(120);
        assertThat(candidate.reputationScore()).isEqualByComparingTo("87.25");
    }
}
