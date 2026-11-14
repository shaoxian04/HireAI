package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * SUBMITTED sweeper (#67): every tick, retries routing for tasks still SUBMITTED past
 * {@code hireai.matching.submitted-min-age}, which means the after-commit routing step failed.
 * Attempts share the re-match counter; exhaustion -> CANCELLED + full refund + client webhook,
 * inside the app service. Per-id work is a cross-bean call with its own transactions, so one
 * poisoned id can't block the rest.
 *
 * <p><b>Single-instance assumption</b> (same as {@link AwaitingAgentRematchSweeper}): the routing writes
 * this drives read the task unlocked, and {@code @Scheduled(fixedDelay)} only serializes within one
 * JVM, so it assumes a SINGLE backend instance.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class SubmittedTaskSweeper {

    private final TaskReliabilityAppService taskReliabilityAppService;

    @Scheduled(fixedDelayString = "${hireai.matching.submitted-sweep-interval:PT10S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every stranded SUBMITTED task. */
    void sweep() {
        List<UUID> stranded = taskReliabilityAppService.strandedSubmittedTaskIds();
        for (UUID id : stranded) {
            try {
                taskReliabilityAppService.rerouteSubmittedOne(id);
            } catch (Exception e) {
                log.warn("SUBMITTED sweeper: failed for task {}", id, e);
            }
        }
        if (!stranded.isEmpty()) {
            log.info("SUBMITTED sweeper: processed {} stranded task(s)", stranded.size());
        }
    }
}
