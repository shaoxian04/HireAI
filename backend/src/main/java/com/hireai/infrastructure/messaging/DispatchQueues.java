package com.hireai.infrastructure.messaging;

/**
 * RabbitMQ topology names for task dispatch (CONTRACTS — Plan 2 owns these; routing never
 * references them, it uses the {@code TaskDispatchPublisher} port). The main queue
 * dead-letters to the DLX/DLQ when listener retries are exhausted.
 */
public final class DispatchQueues {

    public static final String EXCHANGE = "task.dispatch.exchange";
    public static final String QUEUE = "task.dispatch";
    public static final String ROUTING_KEY = "task.dispatch";
    public static final String DLQ = "task.dispatch.dlq";
    public static final String DLX = "task.dispatch.dlx";

    private DispatchQueues() {
    }
}
