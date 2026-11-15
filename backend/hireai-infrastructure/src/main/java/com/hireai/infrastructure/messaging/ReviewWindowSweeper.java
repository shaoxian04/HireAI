package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.TaskReviewAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Review-window sweeper (#94, spec D10): every tick, accepts and settles each PENDING_REVIEW task
 * whose review window ({@code hireai.review.window}) closed without a review, so a Client who never
 * reviews cannot hold a Builder's escrow indefinitely. "Closed" is decided by the app's Clock in the
 * application service. Each autoAcceptOne() is a cross-bean call with its own row-locked
 * transaction: one poisoned id can't roll back settlements already committed for healthy ids, and an
 * overlapping pass (or a Client accepting at the same moment) is a no-op, not a second settlement.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ReviewWindowSweeper {

    private final TaskReviewAppService taskReviewAppService;

    @Scheduled(fixedDelayString = "${hireai.review.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every closed review window. */
    void sweep() {
        List<UUID> expired = taskReviewAppService.reviewExpiredTaskIds();
        for (UUID id : expired) {
            try {
                taskReviewAppService.autoAcceptOne(id);
            } catch (Exception e) {
                log.warn("Review-window sweeper: failed to auto-accept task {}", id, e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Review-window sweeper: processed {} closed review window(s)", expired.size());
        }
    }
}
