package com.hireai.infrastructure.messaging;

import java.time.Duration;

/**
 * RabbitMQ topology names for task dispatch (CONTRACTS — Plan 2 owns these; routing never
 * references them, it uses the {@code TaskDispatchPublisher} port). A failed send is republished
 * to a retry queue (one per configured delay, see {@link #retryQueue(Duration)}) whose
 * per-message TTL dead-letters it back to {@link #EXCHANGE}; the attempt number travels in the
 * {@link #ATTEMPT_HEADER} header. After the last attempt the main queue dead-letters the message
 * to the DLX/DLQ.
 */
public final class DispatchQueues {

    public static final String EXCHANGE = "task.dispatch.exchange";
    public static final String QUEUE = "task.dispatch";
    public static final String ROUTING_KEY = "task.dispatch";
    public static final String DLQ = "task.dispatch.dlq";
    public static final String DLX = "task.dispatch.dlx";
    public static final String RETRY_QUEUE_PREFIX = "task.dispatch.retry.";
    /** 1-based number of the send attempt a message is for; absent on the first attempt. */
    public static final String ATTEMPT_HEADER = "x-hireai-dispatch-attempt";
    /**
     * How many times this send attempt was re-scheduled after a platform-side failure (absent = 0).
     * Separate from {@link #ATTEMPT_HEADER}: a platform failure never consumes an Agent attempt.
     */
    public static final String PLATFORM_RETRY_HEADER = "x-hireai-dispatch-platform-retry";

    private DispatchQueues() {
    }

    /**
     * The retry queue for one delay, e.g. {@code task.dispatch.retry.5000ms}. Each delay gets its
     * own queue so every message in a queue has the same TTL: RabbitMQ expires messages only at
     * the head of a queue, so a short delay queued behind a long one would otherwise wait for it.
     * Naming the queue after its delay also means a changed schedule declares new queues instead
     * of redeclaring an existing one with different arguments.
     */
    public static String retryQueue(Duration delay) {
        return RETRY_QUEUE_PREFIX + delay.toMillis() + "ms";
    }
}
