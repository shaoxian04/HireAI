package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.adjudication.dispute.DisputeAppService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArbitrationSweeperTest {

    @Test
    void escalatesEveryStaleDispute() {
        DisputeAppService service = mock(DisputeAppService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.staleArbitratingDisputeIds(any(Instant.class))).thenReturn(List.of(a, b));

        ArbitrationSweeper sweeper = new ArbitrationSweeper(service, Duration.ofMinutes(2));
        sweeper.sweep();

        verify(service).escalate(a);
        verify(service).escalate(b);
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        DisputeAppService service = mock(DisputeAppService.class);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.staleArbitratingDisputeIds(any(Instant.class))).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(service).escalate(a);

        ArbitrationSweeper sweeper = new ArbitrationSweeper(service, Duration.ofMinutes(2));
        sweeper.sweep();

        verify(service).escalate(b); // b still processed despite a throwing
    }
}
