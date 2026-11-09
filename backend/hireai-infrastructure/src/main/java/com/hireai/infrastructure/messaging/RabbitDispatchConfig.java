package com.hireai.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Declares the task-dispatch RabbitMQ topology: a direct exchange + durable queue bound on
 * {@link DispatchQueues#ROUTING_KEY}, a dead-letter exchange/queue, one retry queue per configured
 * retry delay (dead-lettering back to the dispatch exchange when a message's TTL expires), and a
 * JSON message converter so {@code DispatchMessage} is serialised as JSON. The main queue
 * dead-letters to {@link DispatchQueues#DLX} when the consumer rejects a message after its last
 * attempt; the DLQ has its own listener (see {@code TaskDispatchConsumer}).
 *
 * <p>The dispatch queue is consumed through {@link #dispatchListenerContainerFactory}: no
 * in-listener retry (a failed send goes to a retry queue instead, so it never blocks the
 * consumer), several consumers, and a prefetch of one, so a hanging Agent holds only the one
 * consumer that is waiting on it and no other message is buffered behind it.
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

    /** One durable retry queue per distinct delay; an expired message returns to the dispatch exchange. */
    @Bean
    public Declarables dispatchRetryQueues(DispatchRetrySchedule dispatchRetrySchedule) {
        List<Declarable> queues = dispatchRetrySchedule.distinctDelays().stream()
                .<Declarable>map(delay -> QueueBuilder.durable(DispatchQueues.retryQueue(delay))
                        .deadLetterExchange(DispatchQueues.EXCHANGE)
                        .deadLetterRoutingKey(DispatchQueues.ROUTING_KEY)
                        .build())
                .toList();
        return new Declarables(queues);
    }

    /**
     * Listener container for the dispatch queue. Starts from Spring Boot's settings (message
     * converter, error handling) and then drops the global {@code spring.rabbitmq.listener.simple.retry}
     * advice, which would otherwise block this consumer for the whole retry schedule.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory dispatchListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Value("${hireai.dispatch.consumers:4}") int consumers) {
        if (consumers < 1) {
            throw new IllegalStateException("hireai.dispatch.consumers must be at least 1, got " + consumers);
        }
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain();
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(consumers);
        factory.setMaxConcurrentConsumers(consumers);
        return factory;
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
