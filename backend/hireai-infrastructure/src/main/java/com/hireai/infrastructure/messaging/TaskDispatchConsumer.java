package com.hireai.infrastructure.messaging;

import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.infrastructure.client.AgentDispatchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Consumes dispatch messages: checks under a row lock that the task is still QUEUED (otherwise
 * drops the message), issues a signed token that lives until the task's execution deadline
 * plus {@link DispatchTokenService#CALLBACK_MARGIN}, POSTs the webhook via
 * {@link AgentDispatchClient}, then flips the task to EXECUTING through the
 * {@link TaskExecutionPort} port.
 *
 * <p>A failed send never blocks the consumer: the message is republished to the retry queue for
 * the next delay of the {@link DispatchRetrySchedule}, with the next attempt number in
 * {@link DispatchQueues#ATTEMPT_HEADER}, and acknowledged. When the TTL expires the message
 * returns to the dispatch queue. A failure on the last attempt rejects the message, so it
 * dead-letters to {@link DispatchQueues#DLQ}, where the DLQ listener fails the task (refund,
 * client webhook, {@code EXECUTION_FAILED}). A failure after a successful send is logged and
 * acknowledged, so a WorkOrder that reached the Agent is never sent again. The listener holds no
 * transaction of its own: each port call is its own short transaction.
 */
@Component
@Slf4j
public class TaskDispatchConsumer {

    private final DispatchTokenService dispatchTokenService;
    private final AgentDispatchClient agentDispatchClient;
    private final TaskExecutionPort taskExecutionPort;
    private final RabbitTemplate rabbitTemplate;
    private final DispatchRetrySchedule retrySchedule;

    public TaskDispatchConsumer(DispatchTokenService dispatchTokenService,
                                AgentDispatchClient agentDispatchClient,
                                TaskExecutionPort taskExecutionPort,
                                @Qualifier("dispatchRabbitTemplate") RabbitTemplate rabbitTemplate,
                                DispatchRetrySchedule retrySchedule) {
        this.dispatchTokenService = dispatchTokenService;
        this.agentDispatchClient = agentDispatchClient;
        this.taskExecutionPort = taskExecutionPort;
        this.rabbitTemplate = rabbitTemplate;
        this.retrySchedule = retrySchedule;
    }

    @RabbitListener(queues = DispatchQueues.QUEUE, containerFactory = "dispatchListenerContainerFactory")
    public void onDispatch(DispatchMessage message,
                           @Header(name = DispatchQueues.ATTEMPT_HEADER, required = false) Integer attemptHeader) {
        int attempt = attemptHeader == null ? 1 : attemptHeader;
        try {
            // 1. Row-locked status check: drop the message unless the task is still QUEUED for
            //    this version (a stale message for a refunded or timed-out task, or a retry after
            //    the Agent already called back). It runs again on every attempt.
            if (!taskExecutionPort.isAwaitingDispatch(message.taskId(), message.agentVersionId())) {
                log.info("Task {} is no longer awaiting dispatch to version {}; dropping message",
                        message.taskId(), message.agentVersionId());
                return;
            }
            // 2-3. Issue the token (valid until the execution deadline stamped at assignment plus
            //    the callback margin) and send.
            String token = dispatchTokenService.issue(message.taskId(), message.agentVersionId(),
                    message.payload().executionDeadline());
            agentDispatchClient.dispatch(message, token);
        } catch (RuntimeException e) {
            retryOrDeadLetter(message, attempt, e);
            return;
        }
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
        log.warn("Dispatch for task {} exhausted its attempts; marking FAILED", message.taskId());
        taskExecutionPort.markFailed(message.taskId());
    }

    private void retryOrDeadLetter(DispatchMessage message, int attempt, RuntimeException cause) {
        Optional<Duration> delay = retrySchedule.delayAfterFailedAttempt(attempt);
        if (delay.isEmpty()) {
            log.warn("Dispatch attempt {}/{} for task {} failed; dead-lettering: {}", attempt,
                    retrySchedule.maxAttempts(), message.taskId(), cause.toString());
            throw new AmqpRejectAndDontRequeueException(
                    "Dispatch for task " + message.taskId() + " failed on its last attempt", cause);
        }
        int next = attempt + 1;
        long delayMillis = delay.get().toMillis();
        // Published to the default exchange, which routes by queue name.
        rabbitTemplate.convertAndSend("", DispatchQueues.retryQueue(delay.get()), message, retry -> {
            retry.getMessageProperties().setExpiration(Long.toString(delayMillis));
            retry.getMessageProperties().setHeader(DispatchQueues.ATTEMPT_HEADER, next);
            return retry;
        });
        log.warn("Dispatch attempt {}/{} for task {} failed; retrying in {}ms: {}", attempt,
                retrySchedule.maxAttempts(), message.taskId(), delayMillis, cause.toString());
    }
}
