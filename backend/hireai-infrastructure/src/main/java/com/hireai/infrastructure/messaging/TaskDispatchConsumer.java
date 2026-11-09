package com.hireai.infrastructure.messaging;

import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.infrastructure.client.AgentDispatchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes dispatch messages: checks under a row lock that the task is still QUEUED (otherwise
 * drops the message), issues a short-lived signed token, POSTs the webhook via
 * {@link AgentDispatchClient}, then flips the task to EXECUTING through the
 * {@link TaskExecutionPort} port. A send failure is re-raised so the listener container's
 * bounded retry applies; on exhaustion the message dead-letters to {@link DispatchQueues#DLQ},
 * where the DLQ listener marks the task FAILED. A failure after a successful send is logged and
 * acknowledged, so a WorkOrder that reached the Agent is never sent again. The listener holds no
 * transaction of its own: each port call is its own short transaction.
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
        // 1. Row-locked status check: drop the message unless the task is still QUEUED for this
        //    version (a stale message for a refunded or timed-out task, or a redelivery after the
        //    Agent already called back). It runs again on every retry attempt.
        if (!taskExecutionPort.isAwaitingDispatch(message.taskId(), message.agentVersionId())) {
            log.info("Task {} is no longer awaiting dispatch to version {}; dropping message",
                    message.taskId(), message.agentVersionId());
            return;
        }
        // 2-3. Issue the token and send. A send failure propagates so the listener retry applies.
        String token = dispatchTokenService.issue(
                message.taskId(), message.agentVersionId(), Duration.ofSeconds(tokenTtlSeconds));
        agentDispatchClient.dispatch(message, token);
        // 4. Mark EXECUTING. The WorkOrder has already reached the Agent, so a failure here is
        //    logged and the message acknowledged, never redelivered (a redelivery would send the
        //    WorkOrder twice). The task may stay QUEUED, which is safe: the callback accepts a
        //    result from QUEUED, and the timeout sweeper covers an Agent that never answers.
        try {
            taskExecutionPort.markExecuting(message.taskId());
            log.info("Task {} dispatched and marked EXECUTING", message.taskId());
        } catch (RuntimeException e) {
            log.error("Task {} WorkOrder was delivered but marking EXECUTING failed; acknowledging "
                    + "without redelivery (the task stays QUEUED)", message.taskId(), e);
        }
    }

    @RabbitListener(queues = DispatchQueues.DLQ)
    public void onDeadLetter(DispatchMessage message) {
        log.warn("Dispatch for task {} exhausted retries; marking FAILED", message.taskId());
        taskExecutionPort.markFailed(message.taskId());
    }
}
