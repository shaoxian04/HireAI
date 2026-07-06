package com.hireai.application.biz.task.reliability;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.biz.task.reliability.impl.TaskReliabilityAppServiceImpl;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReliabilityAppServiceImplTest {

    @Mock TaskRepository taskRepository;
    @Mock RoutingAppService routingAppService;
    @Mock TaskWriteAppService taskWriteAppService;
    @Mock SettlementWriteAppService settlementWriteAppService;

    private TaskReliabilityAppServiceImpl service;

    private final UUID taskId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final Money budget = Money.of("100.00");

    @BeforeEach
    void setUp() {
        service = new TaskReliabilityAppServiceImpl(taskRepository, routingAppService,
                taskWriteAppService, settlementWriteAppService, 3);
    }

    private TaskModel taskWithStatus(TaskStatus status) {
        return new TaskModel(taskId, clientId, "title", "desc", budget,
                new OutputSpec(OutputFormat.TEXT, null, "summary"), "general", status,
                null, null, Instant.now(), null, null, null, null);
    }

    /** Builds a real TaskModel in the given status via the canonical constructor. */
    private void givenTaskInStatus(TaskStatus status) {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskWithStatus(status)));
    }

    /** Makes findById return two different states on consecutive calls (pre- and post-routing re-read). */
    private void givenTaskStatusSequence(TaskStatus first, TaskStatus second) {
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(taskWithStatus(first)))
                .thenReturn(Optional.of(taskWithStatus(second)));
    }

    /** timeoutOne loads under a row lock (FIX 2), unlike rematchOne's plain findById. */
    private void givenTaskInStatusForUpdate(TaskStatus status) {
        when(taskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(taskWithStatus(status)));
    }

    @Test
    void rematchSkipsTasksThatAlreadyLeftAwaitingCapacity() {
        givenTaskInStatus(TaskStatus.QUEUED);
        service.rematchOne(taskId);
        verifyNoInteractions(routingAppService);
        verify(taskWriteAppService, never()).registerMatchAttempt(any());
    }

    @Test
    void openTaskRematchRunsFullRouting() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(1);
        service.rematchOne(taskId);
        verify(routingAppService).route(taskId);
        verify(routingAppService, never()).dispatchDirect(any(), any());
    }

    @Test
    void pinnedTaskRematchRetriesOnlyThePinnedVersion() {
        UUID pinned = UUID.randomUUID();
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.of(pinned));
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(1);
        service.rematchOne(taskId);
        verify(routingAppService).dispatchDirect(taskId, pinned);
        verify(routingAppService, never()).route(any());
    }

    @Test
    void exhaustedAttemptsCancelWithRefund() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY); // before AND after routing
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(3);
        service.rematchOne(taskId);
        verify(routingAppService).route(taskId); // last chance still taken
        verify(taskWriteAppService).cancelAwaitingCapacityWithRefund(taskId);
    }

    @Test
    void underAttemptBoundDoesNotCancel() {
        givenTaskInStatus(TaskStatus.AWAITING_CAPACITY);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(2);
        service.rematchOne(taskId);
        verify(taskWriteAppService, never()).cancelAwaitingCapacityWithRefund(any());
    }

    @Test
    void successfulRematchDoesNotCancelEvenAtBound() {
        // status AWAITING_CAPACITY before routing, QUEUED on the re-read after routing
        givenTaskStatusSequence(TaskStatus.AWAITING_CAPACITY, TaskStatus.QUEUED);
        when(taskRepository.findPinnedAgentVersionId(taskId)).thenReturn(Optional.empty());
        when(taskWriteAppService.registerMatchAttempt(taskId)).thenReturn(3);
        service.rematchOne(taskId);
        verify(taskWriteAppService, never()).cancelAwaitingCapacityWithRefund(any());
    }

    @Test
    void timeoutMarksTimedOutAndRefunds() {
        givenTaskInStatusForUpdate(TaskStatus.EXECUTING); // with clientId + budget on the fixture task
        service.timeoutOne(taskId);
        verify(taskRepository).save(argThat(t -> t.status() == TaskStatus.TIMED_OUT));
        verify(settlementWriteAppService).settleRejected(taskId, clientId, budget);
    }

    @Test
    void timeoutIsANoOpOncePastExecuting() {
        givenTaskInStatusForUpdate(TaskStatus.PENDING_REVIEW);
        service.timeoutOne(taskId);
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(settlementWriteAppService);
    }
}
