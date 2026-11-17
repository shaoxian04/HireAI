package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.acceptance.receipt.ReceiptAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Issues the signed receipt (D40) of every settled Item that has none yet, after its settlement
 * committed. Receipts gate nothing, so they are issued here rather than inside the settlement
 * transaction: a receipt failure can never hold up or roll back money. A read of a task's receipts
 * issues missing ones too, so the sweeper only has to catch up on subjects nobody looked at.
 */
@Slf4j
@Component
@Profile("!test")
public class ReceiptIssueSweeper {

    private static final int BATCH = 100;

    private final ReceiptAppService receiptAppService;

    public ReceiptIssueSweeper(ReceiptAppService receiptAppService) {
        this.receiptAppService = receiptAppService;
    }

    @Scheduled(fixedDelayString = "${hireai.receipts.issue-interval:PT30S}")
    public void scheduledSweep() {
        sweep();
    }

    void sweep() {
        try {
            int issued = receiptAppService.issuePending(BATCH);
            if (issued > 0) {
                log.info("Receipt sweeper: issued {} receipt(s)", issued);
            }
        } catch (RuntimeException e) {
            log.warn("Receipt sweeper: pass failed", e);
        }
    }
}
