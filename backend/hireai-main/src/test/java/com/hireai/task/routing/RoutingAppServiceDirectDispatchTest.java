package com.hireai.task.routing;

import com.hireai.application.biz.task.routing.impl.RoutingAppServiceImpl;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the dispatchDirect path of RoutingAppServiceImpl. Plain Mockito — no
 * Spring context, no Docker required.
 */
class RoutingAppServiceDirectDispatchTest {

    private final TaskReadAppService taskReadAppService = mock(TaskReadAppService.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RoutingMatchDomainService routingMatchDomainService = mock(RoutingMatchDomainService.class);
    private final TaskDispatchPublisher taskDispatchPublisher = mock(TaskDispatchPublisher.class);

    private final RoutingAppServiceImpl service = new RoutingAppServiceImpl(
            taskReadAppService, taskWriteAppService, agentRepository,
            routingMatchDomainService, taskDispatchPublisher);

    private static final String TASK_OUTPUT_SPEC = "{\"format\":\"JSON\",\"schema\":\"task-snap\"}";
    private static final String CANDIDATE_OUTPUT_SPEC = "{\"format\":\"TEXT\",\"schema\":\"agent-live\"}";

    private TaskRoutingView view(UUID taskId) {
        return new TaskRoutingView(taskId, "summarisation", new BigDecimal("30.00"), "SUBMITTED",
                TASK_OUTPUT_SPEC);
    }

    private AgentCandidate candidate(UUID versionId) {
        return new AgentCandidate(
                UUID.randomUUID(), versionId, List.of("summarisation"),
                new BigDecimal("10.00"), "https://agent.example/hook", 60, new BigDecimal("80.00"),
                CANDIDATE_OUTPUT_SPEC, 5, 0L, 0L);
    }

    /**
     * Happy path: findCandidateByVersionId returns the candidate → assignAndQueue called BEFORE
     * publish (ordering contract), and the payload carries the task's output spec (Invariant #4).
     */
    @Test
    void happyPathAssignsAndQueuesThenPublishesInThatOrder() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findCandidateByVersionId(versionId)).thenReturn(Optional.of(candidate));

        service.dispatchDirect(taskId, versionId);

        InOrder inOrder = inOrder(taskWriteAppService, taskDispatchPublisher);
        inOrder.verify(taskWriteAppService).assignAndQueue(eq(taskId), eq(versionId), any(Instant.class));
        inOrder.verify(taskDispatchPublisher).publish(any(DispatchMessage.class));
        verify(taskWriteAppService, never()).markAwaitingCapacity(any());
    }

    /**
     * Happy path: dispatch payload carries the TASK's output spec snapshot, not the candidate's
     * live spec (Hard Invariant #4 — the binding contract is the task's adopted copy).
     */
    @Test
    void happyPathPayloadCarriesTaskOutputSpec() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findCandidateByVersionId(versionId)).thenReturn(Optional.of(candidate));

        service.dispatchDirect(taskId, versionId);

        org.mockito.ArgumentCaptor<DispatchMessage> captor =
                org.mockito.ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        DispatchMessage msg = captor.getValue();

        // The payload must carry the task's snapshot, not the candidate's (different) live spec.
        assertThat(msg.payload().outputSpecJson())
                .isEqualTo(TASK_OUTPUT_SPEC)
                .isNotEqualTo(CANDIDATE_OUTPUT_SPEC);
    }

    /**
     * Deactivation race: findCandidateByVersionId returns empty (agent was deactivated/suspended
     * between booking and dispatch). Must mark AWAITING_CAPACITY, NEVER publish, and NOT throw.
     */
    @Test
    void deactivationRaceMarksAwaitingCapacityNeverPublishes() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findCandidateByVersionId(versionId)).thenReturn(Optional.empty());

        // Must not throw.
        service.dispatchDirect(taskId, versionId);

        verify(taskWriteAppService).markAwaitingCapacity(taskId);
        verify(taskDispatchPublisher, never()).publish(any());
        verify(taskWriteAppService, never()).assignAndQueue(any(), any(), any());
    }
}
