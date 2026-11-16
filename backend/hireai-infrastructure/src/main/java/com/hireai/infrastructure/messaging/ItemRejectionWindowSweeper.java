package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.sampling.SamplingAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The windows of rejected Items (spec S8, D17′), on the same tick as {@link RejectionWindowSweeper}:
 * an Agent's silence past its response window counts as standing by; a buyer's silence past their
 * decision window counts as acceptance (and may complete a sample); undelivered notices are retried.
 * Each call is its own row-locked transaction.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ItemRejectionWindowSweeper {

    private final SamplingAppService samplingAppService;

    @Scheduled(fixedDelayString = "${hireai.rejection.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    void sweep() {
        each("stand by silently", samplingAppService.itemResponseExpiredIds(), samplingAppService::standBySilentlyItem);
        each("accept silently", samplingAppService.itemDecisionExpiredIds(), samplingAppService::acceptSilentlyItem);
        each("deliver notice", samplingAppService.itemNoticePendingIds(), samplingAppService::deliverItemNotice);
    }

    private void each(String what, List<UUID> ids, Consumer<UUID> action) {
        for (UUID id : ids) {
            try {
                action.accept(id);
            } catch (Exception e) {
                log.warn("Item rejection sweeper: failed to {} for item {}", what, id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("Item rejection sweeper: {} for {} item(s)", what, ids.size());
        }
    }
}
