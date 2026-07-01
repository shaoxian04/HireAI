package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Human-backstop sweeper: a dispute whose worker ACKed but never posted a ruling never dead-letters,
 * so it sits in ARBITRATING forever. This job flips any ARBITRATING dispute older than
 * {@code hireai.arbitration.stale-after} to ESCALATED so an admin can rule it. Each escalate() is a
 * cross-bean call (own transaction); a late arbitrator callback is guarded out by first-ruling-wins.
 */
@Slf4j
@Component
@Profile("!test")
public class ArbitrationSweeper {

    private final DisputeAppService disputeAppService;
    private final Duration staleAfter;

    public ArbitrationSweeper(DisputeAppService disputeAppService,
                              @Value("${hireai.arbitration.stale-after:PT2M}") Duration staleAfter) {
        this.disputeAppService = disputeAppService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${hireai.arbitration.sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: does one pass, escalating every stale dispute. */
    void sweep() {
        Instant cutoff = Instant.now().minus(staleAfter);
        List<UUID> stale = disputeAppService.staleArbitratingDisputeIds(cutoff);
        for (UUID id : stale) {
            try {
                disputeAppService.escalate(id);
            } catch (Exception e) {
                log.warn("Sweeper: failed to escalate stale dispute {}", id, e);
            }
        }
        if (!stale.isEmpty()) {
            log.info("Sweeper: escalated {} stale ARBITRATING dispute(s) to admin", stale.size());
        }
    }
}
