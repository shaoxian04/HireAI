package com.hireai.dispatch;

import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import com.hireai.support.agent.FakeAgent;
import com.hireai.support.agent.FakeAgentReply;
import com.hireai.support.agent.RecordedWorkOrder;
import com.hireai.support.harness.WholeAppIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Publishes a DispatchMessage through the real publisher bean, over real RabbitMQ, and verifies
 * the consumer issues a token, POSTs the WorkOrder to a fake Agent, and then flips the task to
 * EXECUTING through the {@link StubTaskExecutionPort} (the message names no real task, so the
 * execution port is recorded rather than persisted).
 */
@Import(StubTaskExecutionPort.class)
class DispatchRoundTripIntegrationTest extends WholeAppIntegrationTest {

    @Autowired TaskDispatchPublisher publisher;
    @Autowired StubTaskExecutionPort executionPort;

    @Test
    void publishedDispatchIsDeliveredAndTaskMarkedExecuting() {
        FakeAgent agent = fakeAgent().replyWith(FakeAgentReply.accepted());
        UUID taskId = UUID.randomUUID();
        TaskDispatchPayload payload = new TaskDispatchPayload(
                "Summarise report", "Summarise the attached quarterly report", "summarisation",
                "{\"type\":\"string\"}", "{\"format\":\"JSON\"}",
                platformUrl("/api/agent-callbacks/" + taskId + "/result"));
        DispatchMessage message = new DispatchMessage(
                taskId, UUID.randomUUID(), agent.webhookUrl(), "corr-" + taskId, payload);

        publisher.publish(message);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(executionPort.executing).contains(taskId));

        assertThat(agent.workOrders()).hasSize(1);
        RecordedWorkOrder order = agent.workOrders().get(0);
        assertThat(order.bearerToken()).isNotBlank();
        assertThat(order.header("X-Correlation-ID")).isEqualTo("corr-" + taskId);
        assertThat(order.taskId()).isEqualTo(taskId.toString());
        assertThat(order.field("category")).isEqualTo("summarisation");
        assertThat(order.callbackUrl()).isEqualTo(platformUrl("/api/agent-callbacks/" + taskId + "/result"));
    }
}
