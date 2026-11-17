package com.hireai.infrastructure.adjudication;

import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.application.biz.adjudication.port.ArbitrationDeliverable;
import com.hireai.application.biz.adjudication.port.ArbitrationGateway;
import com.hireai.application.biz.adjudication.port.ArbitrationRequestMessage;
import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.infrastructure.messaging.ArbitrationQueues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Async arbitration adapter (production). Publishes the dispute to the worker queue and returns empty —
 * {@link com.hireai.application.biz.adjudication.dispute.DisputeAppService#openDispute} then moves the
 * dispute to ARBITRATING; the ruling arrives later via the arbitration ruling callback. Active in every
 * profile except {@code test} (where the synchronous {@code StubArbitrationClient} runs instead).
 * <p>
 * Reuses the existing {@code dispatchRabbitTemplate} bean (Jackson2JsonMessageConverter) rather than
 * declaring a second converter/template — the converter serialises any object to JSON, so the same
 * template works for both dispatch and arbitration messages.
 */
@Slf4j
@Component("rabbitArbitrationClient")
@Profile("!test")
public class RabbitArbitrationClient implements ArbitrationGateway {

    private final RabbitTemplate rabbitTemplate;
    private final AcceptanceJsonMapper acceptanceJsonMapper;

    public RabbitArbitrationClient(@Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate,
                                   AcceptanceJsonMapper acceptanceJsonMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.acceptanceJsonMapper = acceptanceJsonMapper;
    }

    @Override
    public Optional<RulingInfo> requestRuling(DisputeModel dispute, TaskModel task, ArbitrationDeliverable deliverable) {
        // The arbitrator judges against the frozen Contract (Invariant #4); format/schema describe it.
        OutputSpec spec = task.outputDescription();
        TaskResultModel result = task.result();
        ArbitrationRequestMessage message = new ArbitrationRequestMessage(
                dispute.id(), task.id(), dispute.correlationId(),
                spec.format().name(), spec.schema(), spec.acceptanceCriteria(), task.description(),
                result == null ? null : result.resultPayloadJson(),
                result == null ? null : result.resultUrl(),
                dispute.reasonCategory().name(),
                deliverable,
                acceptanceJsonMapper.toJson(task.contract()));
        rabbitTemplate.convertAndSend(ArbitrationQueues.EXCHANGE, ArbitrationQueues.ROUTING_KEY, message);
        log.info("Published arbitration request for dispute {} (correlation {})", dispute.id(), dispute.correlationId());
        return Optional.empty();
    }
}
