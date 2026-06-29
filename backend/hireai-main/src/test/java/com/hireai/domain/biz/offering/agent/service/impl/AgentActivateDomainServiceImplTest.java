package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.Pricing;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActivateDomainServiceImplTest {

    private final AgentActivateDomainServiceImpl service = new AgentActivateDomainServiceImpl();

    private AgentModel registered() {
        return AgentModel.register(UUID.randomUUID(), "Bot",
                new OutputSpec(OutputFormat.JSON, null, null),
                List.of("summarisation"), "https://a.example.com", 60, Pricing.of(BigDecimal.ONE));
    }

    @Test
    void activatesPendingAgent() {
        AgentModel active = service.activate(registered());
        assertThat(active.status()).isEqualTo(AgentStatus.ACTIVE);
        assertThat(active.currentVersionId()).isNotNull();
    }

    @Test
    void rejectsAlreadyActiveAgent() {
        AgentModel active = service.activate(registered());
        assertThatThrownBy(() -> service.activate(active)).isInstanceOf(DomainException.class);
    }
}
