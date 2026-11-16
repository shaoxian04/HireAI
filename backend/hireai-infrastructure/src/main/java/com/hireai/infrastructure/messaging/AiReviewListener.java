package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.acceptance.aireview.AiReviewAppService;
import com.hireai.domain.biz.acceptance.aireview.event.AiReviewRequestedDomainEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a requested AI Review (spec S10) once the validation that requested it has committed, off the
 * Agent's callback thread, so a slow model never holds up the callback. A run that fails here is
 * retried by the {@link AiReviewSweeper}. Active in every profile, so the harness exercises it.
 */
@Component
@Slf4j
public class AiReviewListener {

    private final AiReviewAppService aiReviewAppService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, daemonThreads());

    public AiReviewListener(AiReviewAppService aiReviewAppService) {
        this.aiReviewAppService = aiReviewAppService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(AiReviewRequestedDomainEvent event) {
        executor.execute(() -> {
            try {
                aiReviewAppService.run(event.taskId());
            } catch (RuntimeException e) {
                log.warn("Task {}: running the AI Review failed; the sweeper retries it", event.taskId(), e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "ai-review-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
