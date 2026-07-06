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
 * Execution timeout sweeper (spec §6.2): every tick, times out tasks past their execution
 * deadline (QUEUED/EXECUTING -> TIMED_OUT with full escrow refund). Per-id work is a
 * cross-bean call with its own transactions; one poisoned id can't block the rest.
 *
 * <p><b>Single-instance assumption (tracked follow-up, not fixed here):</b> the per-task transition
 * writes this drives ({@code assignAndQueue} / {@code cancelAwaitingCapacityWithRefund}) read the
 * task unlocked in the rematch path, so this sweeper assumes a SINGLE backend instance —
 * {@code @Scheduled(fixedDelay)} only serializes within one JVM. Before scaling to more than one
 * replica, those transition writes must be made status-conditional (or row-locked) to prevent a
 * resurrect race between two overlapping sweeper instances.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ExecutionTimeoutSweeper {

    private final TaskReliabilityAppService taskReliabilityAppService;

    @Scheduled(fixedDelayString = "${hireai.execution.sweep-interval:PT30S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every expired task. */
    void sweep() {
        List<UUID> expired = taskReliabilityAppService.executionExpiredTaskIds();
        for (UUID id : expired) {
            try {
                taskReliabilityAppService.timeoutOne(id);
            } catch (Exception e) {
                log.warn("Timeout sweeper: failed for task {}", id, e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("Timeout sweeper: processed {} expired task(s)", expired.size());
        }
    }
}
