package com.hireai.application.biz.reputation.impl;

import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.domain.biz.offering.agent.info.AgentReputationTarget;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.reputation.enums.ReputationComponent;
import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import com.hireai.domain.biz.reputation.repository.ReputationEventRepository;
import com.hireai.domain.biz.reputation.service.ReputationScoringDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin orchestration only: resolve the agent, enforce the self-dealing exclusion, append the
 * event, and hand the arithmetic to the domain. No scoring rule lives here.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class ReputationWriteAppServiceImpl implements ReputationWriteAppService {

    private final AgentRepository agentRepository;
    private final ReputationEventRepository reputationEventRepository;
    private final ReputationScoringDomainService scoringDomainService;

    @Override
    public void recordOutcome(UUID taskId, UUID agentVersionId, UUID clientId,
                              ReputationEventType type) {
        if (type == null || type.component() != ReputationComponent.RELIABILITY) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "recordOutcome expects a platform-witnessed outcome type; got " + type);
        }
        resolveEmittableAgent(taskId, agentVersionId, clientId, type).ifPresent(target ->
                appendAndRescore(ReputationEventModel.outcome(target.agentId(), taskId, type)));
    }

    @Override
    public void recordRating(UUID taskId, UUID agentVersionId, UUID clientId, int stars) {
        resolveEmittableAgent(taskId, agentVersionId, clientId, ReputationEventType.RATING)
                .ifPresent(target ->
                        appendAndRescore(ReputationEventModel.rating(target.agentId(), taskId, stars)));
    }

    @Override
    public ReputationScore reconcile(UUID agentId) {
        ReputationAggregates replayed = reputationEventRepository.replayAggregates(agentId);
        ReputationScore score = scoringDomainService.score(replayed);
        agentRepository.updateReputation(agentId, replayed, score.score());
        return score;
    }

    /**
     * Returns the agent to credit, or empty when this outcome must not be recorded at all.
     *
     * <p>Two silences live here. A task whose client owns the agent emits nothing — the L1
     * anti-gaming rule, and the cheapest attack on the marketplace. And an outcome already
     * recorded for this task emits nothing again, so a retried settlement cannot double-count;
     * the unique index on (task_id, event_type) is the backstop, but tripping it would roll back
     * the settlement transaction that called us, which is far worse than a no-op.
     */
    private Optional<AgentReputationTarget> resolveEmittableAgent(UUID taskId, UUID agentVersionId,
                                                                 UUID clientId,
                                                                 ReputationEventType type) {
        Optional<AgentReputationTarget> resolved = agentVersionId == null
                ? Optional.empty()
                : agentRepository.findReputationTargetByVersionId(agentVersionId);

        // No resolvable agent means there is nobody to attribute the outcome to — the same
        // situation as a capacity cancellation. Deliberately NOT an exception: emission is a
        // side-effect of a settlement, and reputation bookkeeping must never abort the money path
        // that triggered it (a client's auto-refund is not contingent on us finding an agent row).
        if (resolved.isEmpty()) {
            log.warn("Reputation: no agent for version {} on task {}; emitting nothing",
                    agentVersionId, taskId);
            return Optional.empty();
        }
        AgentReputationTarget target = resolved.get();

        if (target.ownerId().equals(clientId)) {
            log.debug("Reputation: task {} client {} owns agent {}; emitting nothing (L1)",
                    taskId, clientId, target.agentId());
            return Optional.empty();
        }
        if (reputationEventRepository.existsByTaskIdAndEventType(taskId, type)) {
            log.debug("Reputation: task {} already has a {} event; emitting nothing", taskId, type);
            return Optional.empty();
        }
        return Optional.of(target);
    }

    /**
     * Appends the sample and folds it into the running aggregates under a row lock, so concurrent
     * settlements on the same agent serialize instead of losing an increment. O(1) — the agent's
     * history is never re-read on the hot path.
     */
    private void appendAndRescore(ReputationEventModel event) {
        reputationEventRepository.append(event);

        ReputationAggregates current = agentRepository.lockReputationAggregates(event.agentId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent to score: " + event.agentId()));
        ReputationAggregates updated = fold(current, event);

        ReputationScore score = scoringDomainService.score(updated);
        agentRepository.updateReputation(event.agentId(), updated, score.score());

        log.info("Reputation: agent {} {} on task {} (q={}) -> {} [R {} over {}, S {} over {}]",
                event.agentId(), event.eventType(), event.taskId(), event.quality(), score.score(),
                score.reliability(), score.reliabilityCount(),
                score.satisfaction(), score.satisfactionCount());
    }

    private static ReputationAggregates fold(ReputationAggregates current,
                                             ReputationEventModel event) {
        BigDecimal contribution = event.weightedQuality();
        return event.component() == ReputationComponent.SATISFACTION
                ? new ReputationAggregates(
                        current.reliabilitySum(), current.reliabilityCount(),
                        current.satisfactionSum().add(contribution), current.satisfactionCount() + 1)
                : new ReputationAggregates(
                        current.reliabilitySum().add(contribution), current.reliabilityCount() + 1,
                        current.satisfactionSum(), current.satisfactionCount());
    }
}
