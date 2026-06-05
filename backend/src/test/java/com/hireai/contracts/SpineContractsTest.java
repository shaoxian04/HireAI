package com.hireai.contracts;

import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import com.hireai.domain.biz.task.info.TaskRoutingView;
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

    @Test
    void taskRoutingViewExposesAllAccessors() {
        UUID taskId = UUID.randomUUID();
        TaskRoutingView view = new TaskRoutingView(
                taskId, "SUMMARISATION", new BigDecimal("25.00"), "SUBMITTED");

        assertThat(view.taskId()).isEqualTo(taskId);
        assertThat(view.category()).isEqualTo("SUMMARISATION");
        assertThat(view.budget()).isEqualByComparingTo("25.00");
        assertThat(view.status()).isEqualTo("SUBMITTED");
    }

    @Test
    void taskDispatchPayloadExposesAllAccessors() {
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise doc", "Summarise the attached report", "SUMMARISATION",
                "{\"format\":\"TEXT\"}", "{\"format\":\"TEXT\",\"acceptanceCriteria\":\"concise\"}",
                "https://platform.example.com/api/agent-callbacks/abc/result");

        assertThat(payload.title()).isEqualTo("Summarise doc");
        assertThat(payload.description()).isEqualTo("Summarise the attached report");
        assertThat(payload.category()).isEqualTo("SUMMARISATION");
        assertThat(payload.expectedDeliverableJson()).isEqualTo("{\"format\":\"TEXT\"}");
        assertThat(payload.outputSpecJson()).isEqualTo("{\"format\":\"TEXT\",\"acceptanceCriteria\":\"concise\"}");
        assertThat(payload.callbackUrl()).isEqualTo("https://platform.example.com/api/agent-callbacks/abc/result");
    }

    @Test
    void dispatchMessageExposesAllAccessors() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise doc", "Summarise the attached report", "SUMMARISATION",
                "{\"format\":\"TEXT\"}", "{\"format\":\"TEXT\"}",
                "https://platform.example.com/api/agent-callbacks/abc/result");
        DispatchMessage message = new DispatchMessage(
                taskId, agentVersionId, "https://agent.example.com/webhook", "corr-123", payload);

        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.agentVersionId()).isEqualTo(agentVersionId);
        assertThat(message.webhookUrl()).isEqualTo("https://agent.example.com/webhook");
        assertThat(message.correlationId()).isEqualTo("corr-123");
        assertThat(message.payload()).isSameAs(payload);
    }
}
