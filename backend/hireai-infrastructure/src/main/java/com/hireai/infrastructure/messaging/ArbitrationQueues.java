package com.hireai.infrastructure.messaging;

/**
 * RabbitMQ topology names for task dispute arbitration (CONTRACTS). The main queue
 * dead-letters to the DLX/DLQ when listener retries are exhausted.
 */
public final class ArbitrationQueues {
    public static final String EXCHANGE    = "task.dispute.exchange";
    public static final String QUEUE       = "task.dispute.requested";
    public static final String ROUTING_KEY = "task.dispute.requested";
    public static final String DLX         = "task.dispute.dlx";
    public static final String DLQ         = "task.dispute.requested.dlq";

    private ArbitrationQueues() {}
}
