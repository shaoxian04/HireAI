package com.hireai.domain.biz.offering.agent.service.impl;

import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.info.AgentRegisterInfo;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegisterDomainServiceImplTest {

    private final AgentRegisterDomainServiceImpl service = new AgentRegisterDomainServiceImpl();

    private AgentRegisterInfo info(String webhookUrl) {
        return new AgentRegisterInfo(UUID.randomUUID(), "Summariser Bot",
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "valid JSON"),
                List.of("summarisation"), webhookUrl, 120, new BigDecimal("5.00"), 5);
    }

    @Test
    void registersPendingAgentWithVersionOne() {
        AgentModel agent = service.register(info("https://agent.example.com/hook"));

        assertThat(agent.status()).isEqualTo(AgentStatus.PENDING_VERIFICATION);
        assertThat(agent.currentVersion().versionNumber()).isEqualTo(1);
        assertThat(agent.currentVersion().capabilityCategories()).containsExactly("summarisation");
    }

    @Test
    void rejectsNonHttpsWebhook() {
        assertThatThrownBy(() -> service.register(info("http://agent.example.com/hook")))
                .isInstanceOf(DomainException.class);
    }
}
