package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Auto-accept sweeper: a RULED dispute is a PROPOSED ruling awaiting the client. If the client never
 * accepts or appeals, escrow would hang forever. This job auto-accepts any RULED dispute older than
 * {@code hireai.arbitration.ruling-accept-after}, settling from the arbitrator's proposal.
 */
@Slf4j
@Component
@Profile("!test")
public class RulingAcceptSweeper {

    private final DisputeAppService disputeAppService;
    private final Duration acceptAfter;

    public RulingAcceptSweeper(DisputeAppService disputeAppService,
                               @Value("${hireai.arbitration.ruling-accept-after:PT2M}") Duration acceptAfter) {
        this.disputeAppService = disputeAppService;
        this.acceptAfter = acceptAfter;
    }

    @Scheduled(fixedDelayString = "${hireai.arbitration.accept-sweep-interval:PT1M}")
    public void scheduledSweep() {
        sweep();
    }

    /** Package-visible for tests: does one pass, auto-accepting every stale proposed ruling. */
    void sweep() {
        try {
            disputeAppService.autoAcceptStaleRulings(Instant.now().minus(acceptAfter));
        } catch (Exception e) {
            log.warn("Ruling-accept sweeper pass failed", e);
        }
    }
}
