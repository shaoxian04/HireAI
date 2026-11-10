package com.hireai.application.biz.task.routing.impl;

import com.hireai.application.biz.offering.embedding.TaskTextEmbeddingAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.messaging.TaskDispatchPublisher;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.offering.embedding.info.TextVector;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import com.hireai.domain.biz.task.routing.service.RoutingMatchDomainService;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.utility.exception.DomainException;
import lombok.RequiredArgsConstructor;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.dispatchattempt.repository.DispatchAttemptRepository;
import com.hireai.domain.biz.task.routing.service.RoutedFailoverDomainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates routing. Deliberately NOT @Transactional: assignAndQueue owns its own
 * transaction and must COMMIT before publish runs, so the RabbitMQ consumer never races
 * an uncommitted QUEUED state (see plan: "Why publish-after-commit"). publish runs outside
 * any DB transaction because a message send cannot be rolled back. The one exception is
 * {@link #failOver}, which must join the dispatch path's row-locked transaction and publishes
 * from an after-commit hook instead.
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
    private final DispatchAttemptRepository dispatchAttemptRepository;
    private final RoutedFailoverDomainService routedFailoverDomainService;
    /** The task text's vector for the relevance floor and term (#85); empty = rank without relevance. */
    private final TaskTextEmbeddingAppService taskTextEmbeddingAppService;
    /** The app's Clock bean (ClockConfig); tests pass a fixed one to pin the execution deadline. */
    private final Clock clock;

    /**
     * Public base URL the Agent uses to call back. NON-final so @RequiredArgsConstructor takes only the
     * collaborators (the unit test constructs the impl directly); the inline default applies in unit
     * tests (plain `new`, no injection), while Spring overrides it from application.yml at
     * runtime. The property `hireai.platform.public-base-url` is owned by Plan 2's application.yml.
     */
    @org.springframework.beans.factory.annotation.Value("${hireai.platform.public-base-url:http://localhost:8080}")
    private String publicBaseUrl = "http://localhost:8080";

    /** Grace added to the agent's maxExecutionSeconds when stamping the execution deadline. */
    @org.springframework.beans.factory.annotation.Value("${hireai.execution.grace-seconds:60}")
    private long executionGraceSeconds = 60;

    /**
     * Bad config is a startup crash (spec §7): a negative grace would stamp execution deadlines in
     * the past, so every dispatch would be mass-refunded by the timeout sweeper ~30s after routing.
     */
    @jakarta.annotation.PostConstruct
    void validateConfig() {
        if (executionGraceSeconds < 0) {
            throw new IllegalStateException(
                    "hireai.execution.grace-seconds must be >= 0; got " + executionGraceSeconds);
        }
    }

    @Override
    public void route(UUID taskId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        // Cached when a match preview (or an earlier routing pass) embedded the same text; resolved
        // before the selection instant, so time spent on the provider counts against no deadline.
        TextVector taskVector = taskTextEmbeddingAppService.vectorFor(view.title(), view.description()).orElse(null);
        Instant now = selectionInstant();
        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                view.category(), view.budget(), longestExecutionSeconds(view, now), taskVector);
        Optional<UUID> chosen = routingMatchDomainService.selectOne(view, candidates);

        if (chosen.isEmpty()) {
            log.info("No ACTIVE agent matched task {} (category={}, budget={}, clientDeadline={}); "
                    + "marking AWAITING_AGENT", taskId, view.category(), view.budget(), view.clientDeadline());
            taskWriteAppService.markAwaitingAgent(taskId);
            return;
        }

        UUID agentVersionId = chosen.get();
        AgentCandidate winner = candidates.stream()
                .filter(c -> c.agentVersionId().equals(agentVersionId))
                .findFirst()
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Matcher returned an agentVersionId absent from candidates: " + agentVersionId));

        DispatchMessage message = assignAndPublish(view, agentVersionId, winner, now);
        log.info("Task {} assigned to agentVersion {} and dispatch published (correlationId={})",
                taskId, agentVersionId, message.correlationId());
    }

    @Override
    public void dispatchDirect(UUID taskId, UUID agentVersionId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        Optional<AgentCandidate> maybeTarget = agentRepository.findCandidateByVersionId(agentVersionId);
        if (maybeTarget.isEmpty()) {
            // The Agent is no longer ACTIVE, or the pinned version was superseded. A direct booking
            // never substitutes another Agent and the pinned version won't come back, so fail fast:
            // CANCELLED with AGENT_UNAVAILABLE and a refund, so the Client can rebook at once (#77).
            log.warn("AgentVersion {} no longer available (agent not ACTIVE or version superseded); "
                    + "cancelling pinned task {}", agentVersionId, taskId);
            taskWriteAppService.cancelPinnedAsUnavailable(taskId, agentVersionId);
            return;
        }
        DispatchMessage message = assignAndPublish(view, agentVersionId, maybeTarget.get(), selectionInstant());
        log.info("Task {} direct-dispatched to agentVersion {} (correlationId={})",
                taskId, agentVersionId, message.correlationId());
    }

    /**
     * Joins the caller's transaction (MANDATORY: the dispatch path's row-locked transaction that
     * already recorded the failed attempt), so the attempt, the reassignment and the new deadline
     * commit together. The WorkOrder is published only after that commit, exactly like the first
     * dispatch, so the consumer never sees the new version before the row names it.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void failOver(UUID taskId) {
        TaskRoutingView view = taskReadAppService.getRoutingView(taskId);
        List<DispatchAttemptView> history = dispatchAttemptRepository.findByTaskId(taskId);
        // This runs under the task's row lock, so it never waits on the provider: the vector cached by
        // the first routing pass, else no relevance for this hop.
        TextVector taskVector = taskTextEmbeddingAppService.cachedVectorFor(view.title(), view.description())
                .orElse(null);
        // The deadline is re-applied at this hop's own moment: time spent on the failed Agent counts.
        Instant now = selectionInstant();
        List<AgentCandidate> candidates = agentRepository.findActiveCandidates(
                view.category(), view.budget(), longestExecutionSeconds(view, now), taskVector);
        Optional<AgentCandidate> next = routedFailoverDomainService.selectNext(view, history, candidates);
        if (next.isEmpty()) {
            log.info("Task {}: no untried eligible Agent left after {} attempt(s); failing with "
                    + "NO_ALTERNATIVE_AGENT", taskId, history.size());
            taskWriteAppService.failWithNoAlternative(taskId);
            return;
        }
        AgentCandidate target = next.get();
        Instant executionDeadline = deadlineFor(target, now);
        taskWriteAppService.reassign(taskId, target.agentVersionId(), executionDeadline);
        DispatchMessage message = buildDispatchMessage(view, target.agentVersionId(), target, executionDeadline);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    taskDispatchPublisher.publish(message);
                    log.info("Task {} failed over to agentVersion {} and dispatch published (correlationId={})",
                            taskId, target.agentVersionId(), message.correlationId());
                } catch (RuntimeException e) {
                    // The dispatch template already retried the publish. The reassignment committed:
                    // the task stays QUEUED for the new Agent, and the execution-timeout sweeper
                    // refunds it without blaming anyone.
                    log.error("Task {} was reassigned to agentVersion {} but publishing its WorkOrder "
                            + "failed (the task stays QUEUED until its execution timeout)",
                            taskId, target.agentVersionId(), e);
                }
            }
        });
    }

    /**
     * The moment a selection is made. Truncated to microseconds, the precision the task row stores, so
     * the WorkOrder carries exactly the stamped execution deadline. One instant serves both the
     * client-deadline filter and the execution deadline, so an Agent admitted at the deadline's edge
     * never gets an execution deadline after it.
     */
    private Instant selectionInstant() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The candidate query's client-deadline bound at {@code now}; null when the task has no deadline. */
    private Long longestExecutionSeconds(TaskRoutingView view, Instant now) {
        return view.clientDeadline() == null ? null
                : view.clientDeadline().longestExecutionSeconds(now, executionGraceSeconds);
    }

    /** Execution deadline for a dispatch to {@code target} starting at {@code now}. */
    private Instant deadlineFor(AgentCandidate target, Instant now) {
        return now.plusSeconds(target.maxExecutionSeconds() + executionGraceSeconds);
    }

    /**
     * The one path both routed and direct dispatch take, so the WorkOrder can't drift between
     * them. The QUEUED transition (with the execution deadline) commits FIRST in its own
     * transaction so the consumer always sees a durable QUEUED row; only then is the message
     * published.
     */
    private DispatchMessage assignAndPublish(TaskRoutingView view, UUID agentVersionId, AgentCandidate target,
                                             Instant now) {
        Instant executionDeadline = deadlineFor(target, now);
        taskWriteAppService.assignAndQueue(view.taskId(), agentVersionId, executionDeadline);
        DispatchMessage message = buildDispatchMessage(view, agentVersionId, target, executionDeadline);
        taskDispatchPublisher.publish(message);
        return message;
    }

    private DispatchMessage buildDispatchMessage(TaskRoutingView view, UUID agentVersionId,
                                                 AgentCandidate target, Instant executionDeadline) {
        String correlationId = UUID.randomUUID().toString();
        String callbackUrl = publicBaseUrl + "/api/agent-callbacks/" + view.taskId() + "/result";
        TaskDispatchPayload payload = new TaskDispatchPayload(
                view.title(),
                view.description(),
                view.category(),
                // Hard Invariant #4: use the task's adopted copy (frozen at submit time) as the binding
                // contract. The candidate supplies only webhookUrl/version identity — not the spec.
                view.outputSpecJson(),
                executionDeadline,
                callbackUrl);
        return new DispatchMessage(view.taskId(), agentVersionId, target.webhookUrl(), correlationId, payload);
    }
}
