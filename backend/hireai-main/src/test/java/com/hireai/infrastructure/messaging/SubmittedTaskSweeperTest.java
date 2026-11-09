package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmittedTaskSweeperTest {

    @Mock
    private TaskReliabilityAppService taskReliabilityAppService;

    @InjectMocks
    private SubmittedTaskSweeper sweeper;

    @Test
    void oneFailingTaskDoesNotStopTheRestOfTheSweep() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(taskReliabilityAppService.strandedSubmittedTaskIds()).thenReturn(List.of(first, second));
        doThrow(new RuntimeException("boom")).when(taskReliabilityAppService).rerouteSubmittedOne(first);

        sweeper.sweep();

        verify(taskReliabilityAppService).rerouteSubmittedOne(first);
        verify(taskReliabilityAppService).rerouteSubmittedOne(second);
    }
}
