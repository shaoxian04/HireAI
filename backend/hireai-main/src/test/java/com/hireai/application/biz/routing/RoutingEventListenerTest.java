package com.hireai.application.biz.routing;

import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RoutingEventListenerTest {

    private final RoutingAppService routingAppService = mock(RoutingAppService.class);
    private final RoutingEventListener listener = new RoutingEventListener(routingAppService);

    @Test
    void nullDirectAgentVersionIdDelegatesTaskIdToRoute() {
        UUID taskId = UUID.randomUUID();
        TaskSubmittedDomainEvent event = new TaskSubmittedDomainEvent(
                taskId, UUID.randomUUID(), Money.of("30.00"), Instant.now(), null);

        listener.onTaskSubmitted(event);

        verify(routingAppService).route(taskId);
        verify(routingAppService, never()).dispatchDirect(any(), any());
    }

    @Test
    void nonNullDirectAgentVersionIdDelegatesTaskIdToDispatchDirect() {
        UUID taskId = UUID.randomUUID();
        UUID agentVersionId = UUID.randomUUID();
        TaskSubmittedDomainEvent event = new TaskSubmittedDomainEvent(
                taskId, UUID.randomUUID(), Money.of("20.00"), Instant.now(), agentVersionId);

        listener.onTaskSubmitted(event);

        verify(routingAppService).dispatchDirect(taskId, agentVersionId);
        verify(routingAppService, never()).route(any());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
