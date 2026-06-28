package com.hireai.application.biz.routing;

import com.hireai.domain.biz.task.event.TaskSubmittedDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routing trigger seam. Listens for TaskSubmittedDomainEvent and starts routing ONLY
 * AFTER the submit transaction commits (Hard Invariant #1: routing never precedes a
 * committed escrow freeze). Because this fires AFTER_COMMIT, there is no surrounding
 * transaction, so RoutingAppService.route's call to assignAndQueue opens and commits its
 * own transaction before the dispatch message is published.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RoutingEventListener {

    private final RoutingAppService routingAppService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskSubmitted(TaskSubmittedDomainEvent event) {
        if (event.directAgentVersionId() != null) {
            log.info("Task {} submit committed; dispatching directly to version {}",
                    event.taskId(), event.directAgentVersionId());
            routingAppService.dispatchDirect(event.taskId(), event.directAgentVersionId());
            return;
        }
        log.info("Task {} submit committed; starting routing", event.taskId());
        routingAppService.route(event.taskId());
    }
}
