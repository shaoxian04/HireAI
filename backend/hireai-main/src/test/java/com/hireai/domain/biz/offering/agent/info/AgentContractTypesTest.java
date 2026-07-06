package com.hireai.domain.biz.offering.agent.info;

import com.hireai.domain.biz.offering.agent.event.AgentActivatedDomainEvent;
import com.hireai.domain.biz.offering.agent.event.AgentRegisteredDomainEvent;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContractTypesTest {

    @Test
    void agentCandidateCarriesRoutingFields() {
        UUID agentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = new AgentCandidate(agentId, versionId,
                List.of("summarisation"), new BigDecimal("5.00"),
                "https://a.example.com", 120, new BigDecimal("50.00"), "{\"format\":\"JSON\"}",
                5, 0L, 0L);

        assertThat(candidate.agentId()).isEqualTo(agentId);
        assertThat(candidate.agentVersionId()).isEqualTo(versionId);
        assertThat(candidate.capabilityCategories()).containsExactly("summarisation");
        assertThat(candidate.price()).isEqualByComparingTo("5.00");
        assertThat(candidate.webhookUrl()).isEqualTo("https://a.example.com");
        assertThat(candidate.maxExecutionSeconds()).isEqualTo(120);
        assertThat(candidate.reputationScore()).isEqualByComparingTo("50.00");
        assertThat(candidate.outputSpecJson()).isEqualTo("{\"format\":\"JSON\"}");
    }

    @Test
    void registerInfoCarriesAllRegistrationFields() {
        UUID ownerId = UUID.randomUUID();
        OutputSpec spec = new OutputSpec(OutputFormat.JSON, null, null);
        AgentRegisterInfo info = new AgentRegisterInfo(ownerId, "Bot", spec,
                List.of("summarisation"), "https://a.example.com", 60, new BigDecimal("1.00"), 5);

        assertThat(info.ownerId()).isEqualTo(ownerId);
        assertThat(info.name()).isEqualTo("Bot");
        assertThat(info.outputSpec()).isEqualTo(spec);
        assertThat(info.capabilityCategories()).containsExactly("summarisation");
        assertThat(info.webhookUrl()).isEqualTo("https://a.example.com");
        assertThat(info.maxExecutionSeconds()).isEqualTo(60);
        assertThat(info.price()).isEqualByComparingTo("1.00");
        assertThat(info.maxConcurrent()).isEqualTo(5);
    }

    @Test
    void domainEventsCarryIdentifiers() {
        UUID agentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Instant now = Instant.now();

        AgentRegisteredDomainEvent registered =
                new AgentRegisteredDomainEvent(agentId, ownerId, versionId, now);
        AgentActivatedDomainEvent activated =
                new AgentActivatedDomainEvent(agentId, ownerId, versionId, now);

        assertThat(registered.agentId()).isEqualTo(agentId);
        assertThat(registered.ownerId()).isEqualTo(ownerId);
        assertThat(registered.versionId()).isEqualTo(versionId);
        assertThat(activated.agentId()).isEqualTo(agentId);
        assertThat(activated.currentVersionId()).isEqualTo(versionId);
    }

    @Test
    void agentQueryClampsPageAndSize() {
        AgentQuery q = new AgentQuery(-1, 0);
        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(50);
        assertThat(AgentQuery.firstPage().size()).isEqualTo(50);
    }
}
