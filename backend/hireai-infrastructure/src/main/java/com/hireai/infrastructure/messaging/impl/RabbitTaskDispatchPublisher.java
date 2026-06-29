package com.hireai.infrastructure.messaging.impl;

import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.infrastructure.messaging.DispatchQueues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Publishes a {@link DispatchMessage} onto the task-dispatch exchange (implements the
 * Plan 0 {@code TaskDispatchPublisher} port). The JSON converter on the injected template
 * serialises the message; routing supplies the message via the port and never sees RabbitMQ.
 */
@Service
@Slf4j
public class RabbitTaskDispatchPublisher implements TaskDispatchPublisher {

    private final RabbitTemplate rabbitTemplate;

    public RabbitTaskDispatchPublisher(@Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(DispatchMessage message) {
        rabbitTemplate.convertAndSend(DispatchQueues.EXCHANGE, DispatchQueues.ROUTING_KEY, message);
        log.info("Published dispatch for task {} (correlationId={})", message.taskId(), message.correlationId());
    }
}
