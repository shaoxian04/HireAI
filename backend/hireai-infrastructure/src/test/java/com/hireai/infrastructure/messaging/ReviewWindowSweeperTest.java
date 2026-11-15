package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.TaskReviewAppService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sweeper's own job: one failing task must not stop the rest of the pass. */
class ReviewWindowSweeperTest {

    @Test
    void oneFailingTaskDoesNotStopTheRestOfThePass() {
        TaskReviewAppService service = mock(TaskReviewAppService.class);
        UUID poisoned = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(service.reviewExpiredTaskIds()).thenReturn(List.of(poisoned, healthy));
        doThrow(new RuntimeException("boom")).when(service).autoAcceptOne(poisoned);

        new ReviewWindowSweeper(service).sweep();

        verify(service).autoAcceptOne(healthy);
    }
}
