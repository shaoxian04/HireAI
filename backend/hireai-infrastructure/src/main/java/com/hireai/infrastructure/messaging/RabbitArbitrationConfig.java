package com.hireai.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the task-dispute RabbitMQ topology: a direct exchange + durable queue bound on
 * {@link ArbitrationQueues#ROUTING_KEY}, and a dead-letter exchange/queue. The main queue
 * dead-letters to {@link ArbitrationQueues#DLX} when listener retries are exhausted.
 * <p>
 * No dedicated MessageConverter or RabbitTemplate is declared here — the publisher reuses
 * the existing {@code dispatchRabbitTemplate} bean (which already carries a
 * {@code Jackson2JsonMessageConverter}) to keep wiring minimal and consistent.
 */
@Configuration
public class RabbitArbitrationConfig {

    @Bean
    public DirectExchange arbitrationExchange() {
        return new DirectExchange(ArbitrationQueues.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange arbitrationDeadLetterExchange() {
        return new DirectExchange(ArbitrationQueues.DLX, true, false);
    }

    @Bean
    public Queue arbitrationQueue() {
        return QueueBuilder.durable(ArbitrationQueues.QUEUE)
                .deadLetterExchange(ArbitrationQueues.DLX)
                .deadLetterRoutingKey(ArbitrationQueues.DLQ)
                .build();
    }

    @Bean
    public Queue arbitrationDeadLetterQueue() {
        return QueueBuilder.durable(ArbitrationQueues.DLQ).build();
    }

    @Bean
    public Binding arbitrationBinding(Queue arbitrationQueue, DirectExchange arbitrationExchange) {
        return BindingBuilder.bind(arbitrationQueue).to(arbitrationExchange).with(ArbitrationQueues.ROUTING_KEY);
    }

    @Bean
    public Binding arbitrationDeadLetterBinding(Queue arbitrationDeadLetterQueue,
                                                DirectExchange arbitrationDeadLetterExchange) {
        return BindingBuilder.bind(arbitrationDeadLetterQueue).to(arbitrationDeadLetterExchange).with(ArbitrationQueues.DLQ);
    }
}
