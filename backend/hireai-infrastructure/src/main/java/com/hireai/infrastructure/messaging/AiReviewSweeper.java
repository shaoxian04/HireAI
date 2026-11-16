package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.acceptance.aireview.AiReviewAppService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI Review sweeper (spec S10). Every tick it
 * <ol>
 *   <li>runs again the reviews still REQUESTED past {@code hireai.ai-review.retry-after} (the reviewer
 *       failed, or the process stopped mid-run), until they fail after the last attempt;</li>
 *   <li>refunds the fee of reviews whose Task ended before any result passed its checks (timed out,
 *       failed, cancelled).</li>
 * </ol>
 * Each call is its own row-locked transaction, so an overlapping pass is a no-op.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AiReviewSweeper {

    private final AiReviewAppService aiReviewAppService;

    @Scheduled(fixedDelayString = "${hireai.ai-review.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    void sweep() {
        each("re-run", aiReviewAppService.staleRequestedTaskIds(), aiReviewAppService::run);
        each("refund the unused fee of", aiReviewAppService.unusedTaskIds(), aiReviewAppService::releaseIfUnused);
    }

    private void each(String what, List<UUID> ids, Consumer<UUID> action) {
        for (UUID id : ids) {
            try {
                action.accept(id);
            } catch (Exception e) {
                log.warn("AI Review sweeper: failed to {} the AI Review of task {}", what, id, e);
            }
        }
        if (!ids.isEmpty()) {
            log.info("AI Review sweeper: {} {} AI Review(s)", what, ids.size());
        }
    }
}
