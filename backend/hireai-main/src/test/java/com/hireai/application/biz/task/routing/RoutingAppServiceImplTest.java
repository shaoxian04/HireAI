package com.hireai.application.biz.task.routing;

import com.hireai.application.biz.task.routing.impl.RoutingAppServiceImpl;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.routing.service.RoutedFailoverDomainService;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hireai.application.biz.offering.embedding.TaskTextEmbeddingAppService;
import com.hireai.domain.biz.offering.embedding.info.TextVector;

class RoutingAppServiceImplTest {

    private final TaskReadAppService taskReadAppService = mock(TaskReadAppService.class);
    private final TaskWriteAppService taskWriteAppService = mock(TaskWriteAppService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final RoutingMatchDomainService routingMatchDomainService = mock(RoutingMatchDomainService.class);
    private final TaskDispatchPublisher taskDispatchPublisher = mock(TaskDispatchPublisher.class);

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final DispatchAttemptRepository dispatchAttemptRepository = mock(DispatchAttemptRepository.class);
    private final RoutedFailoverDomainService routedFailoverDomainService = mock(RoutedFailoverDomainService.class);
    private final TaskTextEmbeddingAppService taskText = mock(TaskTextEmbeddingAppService.class);

    private final RoutingAppServiceImpl service = new RoutingAppServiceImpl(
            taskReadAppService, taskWriteAppService, agentRepository,
            routingMatchDomainService, taskDispatchPublisher, dispatchAttemptRepository,
            routedFailoverDomainService, taskText, clock, new com.hireai.domain.biz.task.model.ExecutionGrace(60));

    // The task's adopted output spec snapshot (Invariant #4). In unit tests the view carries
    // this directly; candidates supply only webhookUrl/version identity.
    private static final String TASK_OUTPUT_SPEC = "{\"format\":\"TEXT\",\"schema\":\"task-snap\"}";

    private TaskRoutingView view(UUID taskId) {
        return new TaskRoutingView(taskId, "Summarise report", "Summarise the attached report",
                "summarisation", new BigDecimal("30.00"), "SUBMITTED",
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
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00"), null, null))
                .thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        InOrder inOrder = inOrder(taskWriteAppService, taskDispatchPublisher);
        inOrder.verify(taskWriteAppService).assignAndQueue(eq(taskId), eq(versionId), any(Instant.class));
        inOrder.verify(taskDispatchPublisher).publish(any(DispatchMessage.class));
        verify(taskWriteAppService, never()).markAwaitingAgent(any());
    }

    @Test
    void assignAndQueueReceivesDeadlineOfNowPlusMaxExecPlusGrace() {
        // fixture candidate has maxExecutionSeconds 60; the test passes a 60s grace
        // (arrange the existing happy-path mocks so route() reaches assignAndQueue)
        UUID taskId = UUID.randomUUID();
        UUID chosenVersionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(chosenVersionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00"), null, null))
                .thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.of(chosenVersionId));

        service.route(taskId);

        // The clock is fixed at 2030-01-01T00:00:00Z: 60s max execution + 60s grace = 00:02:00.
        verify(taskWriteAppService).assignAndQueue(taskId, chosenVersionId,
                Instant.parse("2030-01-01T00:02:00Z"));
    }

    @Test
    void directDispatchStampsTheDeadlineFromTheInjectedClock() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findCandidateByVersionId(versionId)).thenReturn(Optional.of(candidate(versionId)));

        service.dispatchDirect(taskId, versionId);

        verify(taskWriteAppService).assignAndQueue(taskId, versionId, Instant.parse("2030-01-01T00:02:00Z"));
    }

    @Test
    void onMatchPublishesDispatchMessageWithTaskAndVersionAndWebhook() {
        UUID taskId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        AgentCandidate candidate = candidate(versionId);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(eq("summarisation"), any(), isNull(), isNull())).thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.of(versionId));

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
        when(agentRepository.findActiveCandidates(eq("summarisation"), any(), isNull(), isNull())).thenReturn(List.of(candidate));
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.of(versionId));

        service.route(taskId);

        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        // Must carry the TASK snapshot, not the candidate's (different) spec.
        assertThat(captor.getValue().payload().outputSpecJson())
                .isEqualTo(TASK_OUTPUT_SPEC)
                .isNotEqualTo(CANDIDATE_OUTPUT_SPEC);
    }

    @Test
    void onNoMatchMarksAwaitingAgentAndPublishesNothing() {
        UUID taskId = UUID.randomUUID();
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates(any(), any(), any(), any())).thenReturn(List.of());
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.empty());

        service.route(taskId);

        verify(taskWriteAppService).markAwaitingAgent(taskId);
        verify(taskWriteAppService, never()).assignAndQueue(any(), any(), any());
        verify(taskDispatchPublisher, never()).publish(any());
    }

    // --- Routed failover (#78) ---

    private void stubFailoverInputs(UUID taskId, List<AgentCandidate> candidates) {
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00"), null, null))
                .thenReturn(candidates);
        when(dispatchAttemptRepository.findByTaskId(taskId)).thenReturn(List.of());
    }

    @Test
    void failOverReassignsWithAFreshDeadlineAndPublishesOnlyAfterCommit() {
        UUID taskId = UUID.randomUUID();
        AgentCandidate next = candidate(UUID.randomUUID());
        stubFailoverInputs(taskId, List.of(next));
        when(routedFailoverDomainService.selectNext(any(), any(), any())).thenReturn(Optional.of(next));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.failOver(taskId);

            verify(taskWriteAppService).reassign(taskId, next.agentVersionId(), Instant.parse("2030-01-01T00:02:00Z"));
            verify(taskDispatchPublisher, never()).publish(any());

            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        ArgumentCaptor<DispatchMessage> captor = ArgumentCaptor.forClass(DispatchMessage.class);
        verify(taskDispatchPublisher).publish(captor.capture());
        assertThat(captor.getValue().agentVersionId()).isEqualTo(next.agentVersionId());
        assertThat(captor.getValue().webhookUrl()).isEqualTo(next.webhookUrl());
        assertThat(captor.getValue().payload().executionDeadline()).isEqualTo(Instant.parse("2030-01-01T00:02:00Z"));
        assertThat(captor.getValue().payload().outputSpecJson()).isEqualTo(TASK_OUTPUT_SPEC);
    }

    @Test
    void failOverWithNoAlternativeEndsTheTaskAndPublishesNothing() {
        UUID taskId = UUID.randomUUID();
        stubFailoverInputs(taskId, List.of());
        when(routedFailoverDomainService.selectNext(any(), any(), any())).thenReturn(Optional.empty());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.failOver(taskId);
            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(taskWriteAppService).failWithNoAlternative(taskId);
        verify(taskWriteAppService, never()).reassign(any(), any(), any());
        verify(taskDispatchPublisher, never()).publish(any());
    }

    @Test
    void aPublishFailureAfterCommitIsSwallowed() {
        // The reassignment already committed: the task stays QUEUED for the new Agent and the
        // timeout sweeper refunds it without blame. The caller (the dispatch consumer) must not see it.
        UUID taskId = UUID.randomUUID();
        AgentCandidate next = candidate(UUID.randomUUID());
        stubFailoverInputs(taskId, List.of(next));
        when(routedFailoverDomainService.selectNext(any(), any(), any())).thenReturn(Optional.of(next));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down")).when(taskDispatchPublisher).publish(any());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.failOver(taskId);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(taskDispatchPublisher).publish(any());
    }

    // --- Relevance (#85) ---

    @Test
    void routeComparesCandidatesWithTheTaskTextsVector() {
        UUID taskId = UUID.randomUUID();
        TextVector vector = new TextVector("m", new float[1536]);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(taskText.vectorFor("Summarise report", "Summarise the attached report")).thenReturn(Optional.of(vector));
        when(agentRepository.findActiveCandidates("summarisation", new BigDecimal("30.00"), null, vector))
                .thenReturn(List.of());
        when(routingMatchDomainService.selectOne(any(), any())).thenReturn(Optional.empty());

        service.route(taskId);

        verify(agentRepository).findActiveCandidates("summarisation", new BigDecimal("30.00"), null, vector);
    }

    @Test
    void aFailoverHopUsesOnlyTheCachedVectorAndNeverCallsTheProvider() {
        UUID taskId = UUID.randomUUID();
        TextVector vector = new TextVector("m", new float[1536]);
        when(taskReadAppService.getRoutingView(taskId)).thenReturn(view(taskId));
        when(dispatchAttemptRepository.findByTaskId(taskId)).thenReturn(List.of());
        when(taskText.cachedVectorFor("Summarise report", "Summarise the attached report")).thenReturn(Optional.of(vector));
        when(routedFailoverDomainService.selectNext(any(), any(), any())).thenReturn(Optional.empty());

        service.failOver(taskId);

        verify(agentRepository).findActiveCandidates("summarisation", new BigDecimal("30.00"), null, vector);
        verify(taskText, never()).vectorFor(any(), any());
    }
}
