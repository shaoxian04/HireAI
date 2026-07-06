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
class CapacityRematchSweeperTest {

    @Mock
    private TaskReliabilityAppService taskReliabilityAppService;

    @InjectMocks
    private CapacityRematchSweeper sweeper;

    @Test
    void sweep_calls_rematchOne_for_every_id() {
        // Arrange
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        when(taskReliabilityAppService.awaitingCapacityTaskIds())
                .thenReturn(List.of(id1, id2, id3));

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService).rematchOne(id1);
        verify(taskReliabilityAppService).rematchOne(id2);
        verify(taskReliabilityAppService).rematchOne(id3);
        verifyNoMoreInteractions(taskReliabilityAppService);
    }

    @Test
    void sweep_continues_after_exception_on_first_id() {
        // Arrange
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(taskReliabilityAppService.awaitingCapacityTaskIds())
                .thenReturn(List.of(id1, id2));
        doThrow(new RuntimeException("Test error")).when(taskReliabilityAppService).rematchOne(id1);

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService).rematchOne(id1);
        verify(taskReliabilityAppService).rematchOne(id2);
    }

    @Test
    void sweep_no_calls_on_empty_list() {
        // Arrange
        when(taskReliabilityAppService.awaitingCapacityTaskIds())
                .thenReturn(List.of());

        // Act
        sweeper.sweep();

        // Assert
        verify(taskReliabilityAppService, never()).rematchOne(any());
    }
}
