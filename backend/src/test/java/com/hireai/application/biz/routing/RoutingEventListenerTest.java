package com.hireai.application.biz.routing;

import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoutingEventListenerTest {

    private final RoutingAppService routingAppService = mock(RoutingAppService.class);
    private final RoutingEventListener listener = new RoutingEventListener(routingAppService);

    @Test
    void delegatesTaskIdToRoutingAppService() {
        UUID taskId = UUID.randomUUID();
        TaskSubmittedDomainEvent event = new TaskSubmittedDomainEvent(
                taskId, UUID.randomUUID(), Money.of("30.00"), Instant.now());

        listener.onTaskSubmitted(event);

        verify(routingAppService).route(taskId);
    }
}
