package com.hireai.application.biz.routing.impl;

import com.hireai.application.biz.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.domain.biz.routing.info.DispatchMessage;
import com.hireai.domain.biz.routing.info.TaskDispatchPayload;
import com.hireai.domain.biz.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates routing. Deliberately NOT @Transactional: assignAndQueue owns its own
 * transaction and must COMMIT before publish runs, so the RabbitMQ consumer never races
 * an uncommitted QUEUED state (see plan: "Why publish-after-commit"). publish runs outside
 * any DB transaction because a message send cannot be rolled back.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RoutingAppServiceImpl implements RoutingAppService {

    private final TaskReadAppService taskReadAppService;
    private final TaskWriteAppService taskWriteAppService;
    private final AgentRepository agentRepository;
    private final RoutingMatchDomainService routingMatchDomainService;
    private final TaskDispatchPublisher taskDispatchPublisher;

    /**
     * Public base URL the Agent uses to call back. NON-final so @RequiredArgsConstructor stays
     * 5-arg (the unit test constructs the impl directly); the inline default applies in unit
     * tests (plain `new`, no injection), while Spring overrides it from application.yml at
     * runtime. The property `hireai.platform.public-base-url` is owned by Plan 2's application.yml.
     */
    @org.springframework.beans.factory.annotation.Value("${hireai.platform.public-base-url:http://localhost:8080}")
    private String publicBaseUrl = "http://localhost:8080";

    @Override
    public void route(UUID taskId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        List<AgentCandidate> candidates =
                agentRepository.findActiveCandidates(view.category(), view.budget());
        Optional<UUID> chosen = routingMatchDomainService.selectAgentVersion(view, candidates);

        if (chosen.isEmpty()) {
            log.info("No ACTIVE agent matched task {} (category={}, budget={}); marking AWAITING_CAPACITY",
                    taskId, view.category(), view.budget());
            taskWriteAppService.markAwaitingCapacity(taskId);
            return;
        }

        UUID agentVersionId = chosen.get();
        AgentCandidate winner = candidates.stream()
                .filter(c -> c.agentVersionId().equals(agentVersionId))
                .findFirst()
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Matcher returned an agentVersionId absent from candidates: " + agentVersionId));

        // Commit the QUEUED transition FIRST so the consumer always sees a durable QUEUED row.
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);

        DispatchMessage message = buildDispatchMessage(taskId, agentVersionId, view, winner);
        taskDispatchPublisher.publish(message);
        log.info("Task {} assigned to agentVersion {} and dispatch published (correlationId={})",
                taskId, agentVersionId, message.correlationId());
    }

    @Override
    public void dispatchDirect(UUID taskId, UUID agentVersionId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        Optional<AgentCandidate> maybeTarget = agentRepository.findCandidateByVersionId(agentVersionId);
        if (maybeTarget.isEmpty()) {
            // The agent was deactivated or suspended between booking and dispatch (deactivation race).
            // Mirror route()'s no-match branch: the task stays observable with escrow frozen pending
            // capacity/ops — same contract as the matched path (spec §4.3 step 6).
            log.warn("AgentVersion {} no longer ACTIVE; task {} left in AWAITING_CAPACITY (deactivation race)",
                    agentVersionId, taskId);
            taskWriteAppService.markAwaitingCapacity(taskId);
            return;
        }
        AgentCandidate target = maybeTarget.get();
        // Same ordering contract as route(): QUEUED commits FIRST (REQUIRES_NEW), then publish.
        taskWriteAppService.assignAndQueue(taskId, agentVersionId);
        DispatchMessage message = buildDispatchMessage(taskId, agentVersionId, view, target);
        taskDispatchPublisher.publish(message);
        log.info("Task {} direct-dispatched to agentVersion {} (correlationId={})",
                taskId, agentVersionId, message.correlationId());
    }

    private DispatchMessage buildDispatchMessage(UUID taskId, UUID agentVersionId,
                                                 TaskRoutingView view, AgentCandidate winner) {
        String correlationId = UUID.randomUUID().toString();
        String callbackUrl = publicBaseUrl + "/api/agent-callbacks/" + taskId + "/result";
        TaskDispatchPayload payload = new TaskDispatchPayload(
                view.category(),               // title placeholder uses category in this slice
                view.category(),               // description placeholder
                view.category(),
                null,                          // expectedDeliverableJson: not enriched in this slice
                // Hard Invariant #4: use the task's adopted copy (frozen at submit time) as the binding
                // contract. The candidate supplies only webhookUrl/version identity — not the spec.
                view.outputSpecJson(),
                callbackUrl);
        return new DispatchMessage(taskId, agentVersionId, winner.webhookUrl(), correlationId, payload);
    }
}
