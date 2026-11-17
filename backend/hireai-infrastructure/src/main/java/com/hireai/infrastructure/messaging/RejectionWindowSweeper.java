package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.RejectionAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Rejection-window sweeper (#95, spec D20): every tick it
 * <ol>
 *   <li>counts an Agent's silence past its response window as standing by without a reason;</li>
 *   <li>counts a Client's silence past their decision window as acceptance, and settles it;</li>
 *   <li>retries rejection notices that have not reached the Agent yet (a bounded number of times).</li>
 * </ol>
 * "Past" is decided by the app's Clock in the application service. Each call is its own row-locked
 * transaction, so one bad task can't roll back the others and an overlapping pass is a no-op.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class RejectionWindowSweeper {

    private final RejectionAppService rejectionAppService;

    @Scheduled(fixedDelayString = "${hireai.rejection.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: one pass over all three. */
    void sweep() {
        each("stand by silently", rejectionAppService.responseExpiredTaskIds(), rejectionAppService::standBySilentlyOne);
        each("accept silently", rejectionAppService.decisionExpiredTaskIds(), rejectionAppService::acceptSilentlyOne);
        each("deliver notice", rejectionAppService.noticePendingTaskIds(), rejectionAppService::deliverNotice);
    }

    private void each(String what, java.util.List<UUID> ids, Consumer<UUID> action) {
        for (UUID id : ids) {
            try {
                action.accept(id);
            } catch (Exception e) {
                log.warn("Rejection-window sweeper: failed to {} for task {}", what, id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("Rejection-window sweeper: {} for {} task(s)", what, ids.size());
        }
    }
}
