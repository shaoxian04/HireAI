package com.hireai.domain.biz.offering.agent.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStatusTest {

    @Test
    void declaresTheFourLifecycleStates() {
        assertThat(AgentStatus.values()).containsExactly(
                AgentStatus.PENDING_VERIFICATION,
                AgentStatus.ACTIVE,
                AgentStatus.SUSPENDED,
                AgentStatus.DEACTIVATED);
    }
}
