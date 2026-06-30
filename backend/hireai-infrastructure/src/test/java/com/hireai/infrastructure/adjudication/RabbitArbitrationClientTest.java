package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.shared.model.Money;
import com.hireai.infrastructure.messaging.ArbitrationQueues;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RabbitArbitrationClientTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RabbitArbitrationClient client = new RabbitArbitrationClient(rabbitTemplate);

    @Test
    void publishesRequestAndReturnsEmptyForAsync() {
        UUID clientId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        TaskModel base = TaskModel.submit(clientId, "t", "d", Money.of("100.00"),
                new OutputSpec(OutputFormat.JSON, "{\"type\":\"object\"}", "be correct"), "cat");
        TaskModel task = base.assignAndQueue(agentVersionId).markExecuting()
                .recordResult(TaskResultModel.record(base.id(), "COMPLETED", "{\"a\":1}", null))
                .passValidation()
                .dispute(RejectReason.A_MISMATCH, "wrong");
        DisputeModel dispute = DisputeModel.open(task.id(), clientId, RejectReason.A_MISMATCH, "dispute-" + task.id());

        Optional<RulingInfo> result = client.requestRuling(dispute, task);

        assertThat(result).isEmpty(); // async — ruling arrives via callback
        ArgumentCaptor<ArbitrationRequestMessage> cap = ArgumentCaptor.forClass(ArbitrationRequestMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(ArbitrationQueues.EXCHANGE), eq(ArbitrationQueues.ROUTING_KEY), cap.capture());
        ArbitrationRequestMessage msg = cap.getValue();
        assertThat(msg.disputeId()).isEqualTo(dispute.id());
        assertThat(msg.taskId()).isEqualTo(task.id());
        assertThat(msg.correlationId()).isEqualTo(dispute.correlationId());
        assertThat(msg.format()).isEqualTo("JSON");
        assertThat(msg.schema()).isEqualTo("{\"type\":\"object\"}");
        assertThat(msg.acceptanceCriteria()).isEqualTo("be correct"); // arbitrator sees the subjective criteria
        assertThat(msg.resultPayloadJson()).isEqualTo("{\"a\":1}");
        assertThat(msg.reasonCategory()).isEqualTo("A_MISMATCH");
    }
}
