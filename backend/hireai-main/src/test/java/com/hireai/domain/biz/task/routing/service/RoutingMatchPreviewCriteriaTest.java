package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.PreviewCriteria;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The matcher accepts any MatchCriteria (here a task-less PreviewCriteria) and still budget-filters. */
class RoutingMatchPreviewCriteriaTest {

    private RoutingMatchDomainService greedy() {
        return new RoutingMatchDomainServiceImpl(new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1));
    }

    private AgentCandidate candidate(String price) {
        return new AgentCandidate(UUID.randomUUID(), UUID.randomUUID(), List.of("summarisation"),
                new BigDecimal(price), "https://a.example/hook", 60, new BigDecimal("50.00"),
                "{\"format\":\"JSON\"}", 5, 0, 10);
    }

    @Test
    void rankAcceptsPreviewCriteriaAndFiltersByBudget() {
        List<ScoredCandidate> ranked = greedy().rank(
                new PreviewCriteria("summarisation", new BigDecimal("20.00")),
                List.of(candidate("10.00"), candidate("30.00"))); // 30.00 is over budget -> filtered out
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).candidate().price()).isEqualByComparingTo("10.00");
    }
}
