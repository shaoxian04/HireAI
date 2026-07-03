package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RulingAcceptSweeperTest {
    @Mock DisputeAppService disputeAppService;

    @Test
    void sweep_autoAcceptsWithComputedCutoff() {
        RulingAcceptSweeper sweeper = new RulingAcceptSweeper(disputeAppService, Duration.ofMinutes(2));
        sweeper.sweep();
        verify(disputeAppService).autoAcceptStaleRulings(any(Instant.class));
    }
}
