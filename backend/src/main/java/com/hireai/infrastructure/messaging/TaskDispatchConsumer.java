package com.hireai.infrastructure.messaging;

import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.infrastructure.client.AgentDispatchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes dispatch messages: issues a short-lived signed token, POSTs the webhook via
 * {@link AgentDispatchClient}, then flips the task to EXECUTING through the
 * {@link TaskExecutionPort} port. A thrown exception is re-raised so the listener container's
 * bounded retry applies; on exhaustion the message dead-letters to {@link DispatchQueues#DLQ},
 * where the DLQ listener marks the task FAILED. Plan 2 depends only on the port — no Plan 3 imports.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TaskDispatchConsumer {

    private final DispatchTokenService dispatchTokenService;
    private final AgentDispatchClient agentDispatchClient;
    private final TaskExecutionPort taskExecutionPort;

    @Value("${hireai.dispatch.token-ttl-seconds:900}")
    private long tokenTtlSeconds;

    @RabbitListener(queues = DispatchQueues.QUEUE)
    public void onDispatch(DispatchMessage message) {
        String token = dispatchTokenService.issue(
                message.taskId(), message.agentVersionId(), Duration.ofSeconds(tokenTtlSeconds));
        agentDispatchClient.dispatch(message, token);
        taskExecutionPort.markExecuting(message.taskId());
        log.info("Task {} dispatched and marked EXECUTING", message.taskId());
    }

    @RabbitListener(queues = DispatchQueues.DLQ)
    public void onDeadLetter(DispatchMessage message) {
        log.warn("Dispatch for task {} exhausted retries; marking FAILED", message.taskId());
        taskExecutionPort.markFailed(message.taskId());
    }
}
