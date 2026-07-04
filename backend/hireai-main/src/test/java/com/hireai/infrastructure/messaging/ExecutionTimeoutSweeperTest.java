package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.task.reliability.TaskReliabilityAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExecutionTimeoutSweeperTest {

    @Mock
    private TaskReliabilityAppService taskReliabilityAppService;

    @InjectMocks
    private ExecutionTimeoutSweeper sweeper;

    @Test
    void sweep_calls_timeoutOne_for_every_id() {
        // Arrange
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        when(taskReliabilityAppService.executionExpiredTaskIds())
                .thenReturn(List.of(id1, id2, id3));

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService).timeoutOne(id1);
        verify(taskReliabilityAppService).timeoutOne(id2);
        verify(taskReliabilityAppService).timeoutOne(id3);
        verifyNoMoreInteractions(taskReliabilityAppService);
    }

    @Test
    void sweep_continues_after_exception_on_first_id() {
        // Arrange
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(taskReliabilityAppService.executionExpiredTaskIds())
                .thenReturn(List.of(id1, id2));
        doThrow(new RuntimeException("Test error")).when(taskReliabilityAppService).timeoutOne(id1);

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService).timeoutOne(id1);
        verify(taskReliabilityAppService).timeoutOne(id2);
    }

    @Test
    void sweep_no_calls_on_empty_list() {
        // Arrange
        when(taskReliabilityAppService.executionExpiredTaskIds())
                .thenReturn(List.of());

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService, never()).timeoutOne(any());
    }
}
