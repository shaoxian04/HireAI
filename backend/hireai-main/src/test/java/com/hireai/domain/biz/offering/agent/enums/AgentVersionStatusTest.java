package com.hireai.domain.biz.offering.agent.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionStatusTest {

    @Test
    void declaresLifecycleStates() {
        assertThat(AgentVersionStatus.values()).containsExactly(
                AgentVersionStatus.DRAFT,
                AgentVersionStatus.ACTIVE,
                AgentVersionStatus.DEPRECATED);
    }
}
