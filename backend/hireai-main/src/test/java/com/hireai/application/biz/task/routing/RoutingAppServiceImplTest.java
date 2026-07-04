package com.hireai.application.biz.task.routing;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
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

class RoutingAppServiceImplTest {

    private final TaskReadAppService taskReadAppService = mock(TaskReadAppService.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RoutingMatchDomainService routingMatchDomainService = mock(RoutingMatchDomainService.class);
    private final TaskDispatchPublisher taskDispatchPublisher = mock(TaskDispatchPublisher.class);

    private final RoutingAppServiceImpl service = new RoutingAppServiceImpl(
            taskReadAppService, taskWriteAppService, agentRepository,
            routingMatchDomainService, taskDispatchPublisher);

    // The task's adopted output spec snapshot (Invariant #4). In unit tests the view carries
    // this directly; candidates supply only webhookUrl/version identity.
    private static final String TASK_OUTPUT_SPEC = "{\"format\":\"TEXT\",\"schema\":\"task-snap\"}";

    private TaskRoutingView view(UUID taskId) {
        return new TaskRoutingView(taskId, "summarisation", new BigDecimal("30.00"), "SUBMITTED",
                TASK_OUTPUT_SPEC);
    }

    private static final String CANDIDATE_OUTPUT_SPEC = "{\"format\":\"JSON\"}";

    private AgentCandidate candidate(UUID versionId) {
        return new AgentCandidate(
                UUID.randomUUID(), versionId, List.of("summarisation"),
                new BigDecimal("10.00"), "https://agent.example/hook", 60, new BigDecimal("80.00"),
                CANDIDATE_OUTPUT_SPEC, 5, 0L, 0L);
    }

    @Test
    void onMatchAssignsAndQueuesThenPublishesInThatOrder() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00")))
                .thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        InOrder inOrder = inOrder(taskWriteAppService, taskDispatchPublisher);
        inOrder.verify(taskWriteAppService).assignAndQueue(taskId, versionId);
        inOrder.verify(taskDispatchPublisher).publish(any(DispatchMessage.class));
        verify(taskWriteAppService, never()).markAwaitingCapacity(any());
    }

    @Test
    void onMatchPublishesDispatchMessageWithTaskAndVersionAndWebhook() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(eq("summarisation"), any())).thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        DispatchMessage message = captor.getValue();
        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.agentVersionId()).isEqualTo(versionId);
        assertThat(message.webhookUrl()).isEqualTo("https://agent.example/hook");
        assertThat(message.correlationId()).isNotBlank();
        assertThat(message.payload().category()).isEqualTo("summarisation");
    }

    @Test
    void onMatchPublishesDispatchPayloadCarryingTaskOutputSpec() {
        // Invariant #4: the task's adopted snapshot (view.outputSpecJson) is the binding contract,
        // NOT the candidate's live spec. The two are deliberately different here to pin the contract.
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(eq("summarisation"), any())).thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        // Must carry the TASK snapshot, not the candidate's (different) spec.
        assertThat(captor.getValue().payload().outputSpecJson())
                .isEqualTo(TASK_OUTPUT_SPEC)
                .isNotEqualTo(CANDIDATE_OUTPUT_SPEC);
    }

    @Test
    void onNoMatchMarksAwaitingCapacityAndPublishesNothing() {
        UUID taskId = UUID.randomUUID();
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(any(), any())).thenReturn(List.of());
        when(routingMatchDomainService.selectAgentVersion(any(), any())).thenReturn(Optional.empty());

        service.route(taskId);

        verify(taskWriteAppService).markAwaitingCapacity(taskId);
        verify(taskWriteAppService, never()).assignAndQueue(any(), any());
        verify(taskDispatchPublisher, never()).publish(any());
    }
}
