package com.hireai.infrastructure.messaging;

import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.application.port.task.TaskExecutionPort;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.infrastructure.client.AgentDispatchClient;
import com.hireai.infrastructure.client.AgentSendFailedException;
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
 * <p>Only an Agent-attributable send failure ({@link AgentSendFailedException}: unreachable,
 * read timeout, non-2xx) consumes an attempt. A platform-side failure before or around the send
 * (task lookup, token issue, scheduling the retry) is logged and the message acknowledged; the
 * task stays QUEUED and the execution-timeout sweeper refunds it without blaming the Agent.
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
            //    the callback margin) and send. Only a failure of the send itself is the Agent's
            //    and consumes an attempt.
            String token = dispatchTokenService.issue(message.taskId(), message.agentVersionId(),
                    message.payload().executionDeadline());
            agentDispatchClient.dispatch(message, token);
        } catch (AgentSendFailedException e) {
            retryOrDeadLetter(message, attempt, e);
            return;
        } catch (RuntimeException e) {
            // A platform-side failure (task lookup, token issue, an invalid URL) is never blamed
            // on the Agent: it neither consumes an attempt nor dead-letters (which would record
            // EXECUTION_FAILED). The message is acknowledged and the task stays QUEUED; the
            // execution-timeout sweeper then refunds it without a reputation event.
            log.error("Dispatch of task {} failed on the platform side before the send; dropping "
                    + "the message (the task stays QUEUED until its execution timeout)", message.taskId(), e);
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

    // The seam for busy replies (#76): AgentSendFailedException.statusCode() tells 429/503 apart.
    private void retryOrDeadLetter(DispatchMessage message, int attempt, AgentSendFailedException cause) {
        Optional<Duration> delay = retrySchedule.delayAfterFailedAttempt(attempt);
        if (delay.isEmpty()) {
            log.warn("Dispatch attempt {}/{} for task {} failed; dead-lettering: {}", attempt,
                    retrySchedule.maxAttempts(), message.taskId(), cause.toString());
            throw new AmqpRejectAndDontRequeueException(
                    "Dispatch for task " + message.taskId() + " failed on its last attempt", cause);
        }
        int next = attempt + 1;
        long delayMillis = delay.get().toMillis();
        try {
            // Published to the default exchange, which routes by queue name.
            rabbitTemplate.convertAndSend("", DispatchQueues.retryQueue(delay.get()), message, retry -> {
                retry.getMessageProperties().setExpiration(Long.toString(delayMillis));
                retry.getMessageProperties().setHeader(DispatchQueues.ATTEMPT_HEADER, next);
                return retry;
            });
        } catch (RuntimeException publishFailure) {
            // Platform-side: never dead-letter (that would blame the Agent). The task stays
            // QUEUED and the execution-timeout sweeper refunds it without a reputation event.
            log.error("Could not schedule dispatch retry for task {}; dropping the message (the task "
                    + "stays QUEUED until its execution timeout)", message.taskId(), publishFailure);
            return;
        }
        log.warn("Dispatch attempt {}/{} for task {} failed; retrying in {}ms: {}", attempt,
                retrySchedule.maxAttempts(), message.taskId(), delayMillis, cause.toString());
    }
}
