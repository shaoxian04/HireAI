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
 * Re-match sweeper (spec §6.1): every tick, retries matching for every AWAITING_CAPACITY task
 * (attempt-bounded; exhaustion -> CANCELLED + full refund inside the app service). Per-id work is
 * a cross-bean call with its own transactions; one poisoned id can't block the rest.
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
public class CapacityRematchSweeper {

    private final TaskReliabilityAppService taskReliabilityAppService;

    @Scheduled(fixedDelayString = "${hireai.matching.rematch-interval:PT10S}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over every held task. */
    void sweep() {
        List<UUID> held = taskReliabilityAppService.awaitingCapacityTaskIds();
        for (UUID id : held) {
            try {
                taskReliabilityAppService.rematchOne(id);
            } catch (Exception e) {
                log.warn("Re-match sweeper: failed for task {}", id, e);
            }
        }
        if (!held.isEmpty()) {
            log.info("Re-match sweeper: processed {} held task(s)", held.size());
        }
    }
}
