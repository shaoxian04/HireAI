package com.hireai.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the task-dispatch RabbitMQ topology: a direct exchange + durable queue bound on
 * {@link DispatchQueues#ROUTING_KEY}, a dead-letter exchange/queue, and a JSON message
 * converter so {@code DispatchMessage} is serialised as JSON. The main queue dead-letters
 * to {@link DispatchQueues#DLX} when listener retries are exhausted; the DLQ has its own
 * listener (see {@code TaskDispatchConsumer}).
 */
@Configuration
public class RabbitDispatchConfig {

    @Bean
    public DirectExchange dispatchExchange() {
        return new DirectExchange(DispatchQueues.EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dispatchDeadLetterExchange() {
        return new DirectExchange(DispatchQueues.DLX, true, false);
    }

    @Bean
    public Queue dispatchQueue() {
        return QueueBuilder.durable(DispatchQueues.QUEUE)
                .deadLetterExchange(DispatchQueues.DLX)
                .deadLetterRoutingKey(DispatchQueues.DLQ)
                .build();
    }

    @Bean
    public Queue dispatchDeadLetterQueue() {
        return QueueBuilder.durable(DispatchQueues.DLQ).build();
    }

    @Bean
    public Binding dispatchBinding(Queue dispatchQueue, DirectExchange dispatchExchange) {
        return BindingBuilder.bind(dispatchQueue).to(dispatchExchange).with(DispatchQueues.ROUTING_KEY);
    }

    @Bean
    public Binding dispatchDeadLetterBinding(Queue dispatchDeadLetterQueue,
                                             DirectExchange dispatchDeadLetterExchange) {
        return BindingBuilder.bind(dispatchDeadLetterQueue).to(dispatchDeadLetterExchange).with(DispatchQueues.DLQ);
    }

    @Bean
    public MessageConverter dispatchMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate dispatchRabbitTemplate(ConnectionFactory connectionFactory,
                                                 MessageConverter dispatchMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(dispatchMessageConverter);
        template.setExchange(DispatchQueues.EXCHANGE);
        template.setRoutingKey(DispatchQueues.ROUTING_KEY);
        return template;
    }
}
