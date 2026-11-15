package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.RejectionAppService;
import com.hireai.domain.biz.task.event.TaskRejectedDomainEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the Agent its rejection notice (#95) once the Client's rejection has committed, off the
 * request thread, so a slow Agent webhook never holds up the Client's reject. A notice that fails
 * here is retried by the {@link RejectionWindowSweeper}; the Agent's silence counts as standing by
 * once its response window closes, so a notice that never arrives costs the Client nothing.
 */
@Component
@Slf4j
public class RejectionNoticeListener {

    private final RejectionAppService rejectionAppService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, daemonThreads());

    public RejectionNoticeListener(RejectionAppService rejectionAppService) {
        this.rejectionAppService = rejectionAppService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskRejected(TaskRejectedDomainEvent event) {
        executor.execute(() -> {
            try {
                rejectionAppService.deliverNotice(event.taskId());
            } catch (RuntimeException e) {
                log.warn("Task {}: sending the rejection notice failed; the sweeper retries it", event.taskId(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static java.util.concurrent.ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "rejection-notice-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
