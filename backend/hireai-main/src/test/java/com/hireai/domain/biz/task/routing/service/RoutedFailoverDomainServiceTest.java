package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.dispatchattempt.enums.DispatchOutcome;
import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.service.impl.RoutedFailoverDomainServiceImpl;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Picking the next Agent for a routed task whose Agent could not take it (#78). */
class RoutedFailoverDomainServiceTest {

    private static final PreviewCriteria CRITERIA = new PreviewCriteria("general", new BigDecimal("50.00"));

    // Epsilon 0 keeps selection greedy, so the outcome is the best untried candidate.
    private final RoutedFailoverDomainService service = new RoutedFailoverDomainServiceImpl(
            new RoutingMatchDomainServiceImpl(MatchingPolicy.defaults().withEpsilon(0.0), new Random(7)));

    private static AgentCandidate candidate(UUID agentId, String price) {
        return new AgentCandidate(agentId, UUID.randomUUID(), List.of("general"), new BigDecimal(price),
                "https://agent.example.com/hook", 60, new BigDecimal("50.00"), "{}", 5, 0, 10);
    }

    private static DispatchAttemptView tried(AgentCandidate c, int n, DispatchOutcome outcome) {
        return new DispatchAttemptView(n, c.agentId(), c.agentVersionId(), outcome, Instant.now());
    }

    @Test
    void picksAnUntriedEligibleAgent() {
        AgentCandidate first = candidate(UUID.randomUUID(), "10.00");
        AgentCandidate second = candidate(UUID.randomUUID(), "20.00");

        Optional<AgentCandidate> next = service.selectNext(CRITERIA,
                List.of(tried(first, 1, DispatchOutcome.BUSY)), List.of(first, second));

        assertThat(next).contains(second);
    }

    @Test
    void neverPicksAnAgentAlreadyTriedEvenUnderAnotherVersion() {
        UUID agentId = UUID.randomUUID();
        AgentCandidate oldVersion = candidate(agentId, "10.00");
        AgentCandidate newVersion = candidate(agentId, "10.00");

        Optional<AgentCandidate> next = service.selectNext(CRITERIA,
                List.of(tried(oldVersion, 1, DispatchOutcome.UNREACHABLE)), List.of(newVersion));

        assertThat(next).isEmpty();
    }

    @Test
    void neverPicksAVersionAlreadyTriedWhenItsAgentIsUnknown() {
        AgentCandidate c = candidate(UUID.randomUUID(), "10.00");
        DispatchAttemptView orphan = new DispatchAttemptView(1, null, c.agentVersionId(),
                DispatchOutcome.BUSY, Instant.now());

        assertThat(service.selectNext(CRITERIA, List.of(orphan), List.of(c))).isEmpty();
    }

    @Test
    void returnsNothingWhenNoUntriedCandidateIsLeft() {
        AgentCandidate only = candidate(UUID.randomUUID(), "10.00");

        assertThat(service.selectNext(CRITERIA, List.of(tried(only, 1, DispatchOutcome.BUSY)), List.of(only)))
                .isEmpty();
    }

    @Test
    void stopsAfterThreeDistinctAgentsEvenWithCandidatesLeft() {
        List<AgentCandidate> candidates = new ArrayList<>();
        List<DispatchAttemptView> history = new ArrayList<>();
        for (int i = 1; i <= RoutedFailoverDomainService.MAX_DISTINCT_AGENTS; i++) {
            AgentCandidate c = candidate(UUID.randomUUID(), "10.00");
            candidates.add(c);
            history.add(tried(c, i, DispatchOutcome.UNREACHABLE));
        }
        candidates.add(candidate(UUID.randomUUID(), "10.00"));

        assertThat(service.selectNext(CRITERIA, history, candidates)).isEmpty();
    }

    @Test
    void twoAttemptsOnOneAgentCountAsOneAgentTowardsTheLimit() {
        AgentCandidate a = candidate(UUID.randomUUID(), "10.00");
        AgentCandidate b = candidate(UUID.randomUUID(), "10.00");
        AgentCandidate c = candidate(UUID.randomUUID(), "10.00");
        List<DispatchAttemptView> history = List.of(
                tried(a, 1, DispatchOutcome.BUSY), tried(a, 2, DispatchOutcome.BUSY), tried(b, 3, DispatchOutcome.BUSY));

        assertThat(service.selectNext(CRITERIA, history, List.of(a, b, c))).contains(c);
    }

    @Test
    void appliesTheSameBudgetFilterAsInitialMatching() {
        AgentCandidate first = candidate(UUID.randomUUID(), "10.00");
        AgentCandidate overBudget = candidate(UUID.randomUUID(), "80.00");

        assertThat(service.selectNext(CRITERIA, List.of(tried(first, 1, DispatchOutcome.BUSY)),
                List.of(first, overBudget))).isEmpty();
    }

    @Test
    void anUntriedAgentRuledOutOnlyByTheDeadlineIsNoEligibleAgent() {
        AgentCandidate first = candidate(UUID.randomUUID(), "10.00");
        AgentCandidate tooSlow = candidate(UUID.randomUUID(), "20.00");

        assertThat(service.exhaustionReason(List.of(tried(first, 1, DispatchOutcome.BUSY)), List.of(first, tooSlow)))
                .isEqualTo(FailureReason.NO_ELIGIBLE_AGENT);
    }

    @Test
    void nobodyUntriedLeftIsNoAlternativeAgentEvenWithADeadline() {
        AgentCandidate only = candidate(UUID.randomUUID(), "10.00");

        assertThat(service.exhaustionReason(List.of(tried(only, 1, DispatchOutcome.BUSY)), List.of(only)))
                .isEqualTo(FailureReason.NO_ALTERNATIVE_AGENT);
    }

    @Test
    void withoutADeadlineTheReasonIsNoAlternativeAgent() {
        AgentCandidate only = candidate(UUID.randomUUID(), "10.00");

        assertThat(service.exhaustionReason(List.of(tried(only, 1, DispatchOutcome.BUSY)), null))
                .isEqualTo(FailureReason.NO_ALTERNATIVE_AGENT);
    }

    @Test
    void theAgentLimitIsNoAlternativeAgentEvenIfASlowAgentIsLeft() {
        List<DispatchAttemptView> history = new ArrayList<>();
        for (int i = 1; i <= RoutedFailoverDomainService.MAX_DISTINCT_AGENTS; i++) {
            history.add(tried(candidate(UUID.randomUUID(), "10.00"), i, DispatchOutcome.UNREACHABLE));
        }

        assertThat(service.exhaustionReason(history, List.of(candidate(UUID.randomUUID(), "20.00"))))
                .isEqualTo(FailureReason.NO_ALTERNATIVE_AGENT);
    }
}
