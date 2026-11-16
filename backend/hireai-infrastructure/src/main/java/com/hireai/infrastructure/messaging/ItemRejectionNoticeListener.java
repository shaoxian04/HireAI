package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.sampling.SamplingAppService;
import com.hireai.domain.biz.task.event.ItemRejectedDomainEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends the Agent a rejected Item's notice (spec S8, D17′) once the reviewer's judgement has committed,
 * off the request thread. A notice that fails here is retried by the {@link ItemRejectionWindowSweeper};
 * the Agent's silence counts as standing by once its window closes.
 */
@Component
@Slf4j
public class ItemRejectionNoticeListener {

    private final SamplingAppService samplingAppService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, daemonThreads());

    public ItemRejectionNoticeListener(SamplingAppService samplingAppService) {
        this.samplingAppService = samplingAppService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemRejected(ItemRejectedDomainEvent event) {
        executor.execute(() -> {
            try {
                samplingAppService.deliverItemNotice(event.itemId());
            } catch (RuntimeException e) {
                log.warn("Task {}: sending an Item's rejection notice failed; the sweeper retries it",
                        event.taskId(), e);
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
            Thread thread = new Thread(runnable, "item-rejection-notice-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
