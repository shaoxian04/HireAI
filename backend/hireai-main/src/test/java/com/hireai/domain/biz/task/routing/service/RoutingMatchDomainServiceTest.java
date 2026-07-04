package com.hireai.domain.biz.task.routing.service;

import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.routing.info.ScoredCandidate;
import com.hireai.domain.biz.task.routing.service.impl.RoutingMatchDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class RoutingMatchDomainServiceTest {

    private static final MatchingPolicy POLICY = MatchingPolicy.defaults();

    /** epsilon=0 => pure argmax; seed irrelevant. The standard fixture for scoring tests. */
    private RoutingMatchDomainService greedy() {
        return new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 0.0), new Random(1));
    }

    private TaskRoutingView task(String category, String budget) {
        return new TaskRoutingView(UUID.randomUUID(), category, new BigDecimal(budget), "SUBMITTED",
                "{\"format\":\"JSON\"}");
    }

    private AgentCandidate candidate(UUID versionId, List<String> categories, String price,
                                     String reputation, int maxConcurrent, long inFlight, long sampleCount) {
        return new AgentCandidate(UUID.randomUUID(), versionId, categories,
                new BigDecimal(price), "https://agent.example/hook", 60, new BigDecimal(reputation),
                "{\"format\":\"JSON\"}", maxConcurrent, inFlight, sampleCount);
    }

    /** Baseline candidate: everything equal so single factors can be varied per test. */
    private AgentCandidate base(UUID versionId, String price, String rep, long inFlight, long samples) {
        return candidate(versionId, List.of("summarisation"), price, rep, 5, inFlight, samples);
    }

    // ---- spec test 1: each factor in isolation decides the winner ----

    @Test
    void higherReputationWinsWhenOtherFactorsEqual() {
        UUID better = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 0, 10),
                base(better, "10.00", "90.00", 0, 10)));
        assertThat(chosen).contains(better);
    }

    @Test
    void cheaperPriceWinsWhenOtherFactorsEqual() {
        UUID cheaper = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "25.00", "50.00", 0, 10),
                base(cheaper, "10.00", "50.00", 0, 10)));
        assertThat(chosen).contains(cheaper);
    }

    @Test
    void idleAgentBeatsBusyAgentWhenOtherFactorsEqual() {
        UUID idle = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 4, 10),
                base(idle, "10.00", "50.00", 0, 10)));
        assertThat(chosen).contains(idle);
    }

    @Test
    void underSampledAgentBeatsVeteranWhenOtherFactorsEqual() {
        UUID newcomer = UUID.randomUUID();
        Optional<UUID> chosen = greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "10.00", "50.00", 0, 100),
                base(newcomer, "10.00", "50.00", 0, 0)));
        assertThat(chosen).contains(newcomer);
    }

    // ---- spec test 2: composite score equals a hand-computed value ----

    @Test
    void scoreMatchesHandComputedValue() {
        // rep 90 -> 0.40*0.90 = 0.36 ; price 20/budget 30 -> valueFit (30-20)/30 = 1/3 -> 0.20*(1/3)
        // inFlight 1 / maxConcurrent 5 -> headroom 0.8 -> 0.20*0.8 = 0.16 ; samples 4 -> 1/5 -> 0.20*0.2 = 0.04
        AgentCandidate c = candidate(UUID.randomUUID(), List.of("summarisation"),
                "20.00", "90.00", 5, 1, 4);
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(c));
        double expected = 0.36 + 0.20 * (1.0 / 3.0) + 0.16 + 0.04;
        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).score()).isCloseTo(expected, offset(1e-9));
    }

    // ---- spec test 3: rank returns all eligible, sorted, scored ----

    @Test
    void rankReturnsAllEligibleSortedBestFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                base(b, "25.00", "50.00", 0, 10),   // worse valueFit
                base(a, "10.00", "50.00", 0, 10),
                candidate(UUID.randomUUID(), List.of("translation"), "10.00", "99.00", 5, 0, 0))); // filtered
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).candidate().agentVersionId()).isEqualTo(a);
        assertThat(ranked.get(1).candidate().agentVersionId()).isEqualTo(b);
        assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
    }

    // ---- spec tests 5-6: filtering + empties (regression of existing behaviour) ----

    @Test
    void returnsEmptyWhenNoCandidateMatchesCategory() {
        assertThat(greedy().selectOne(task("summarisation", "30.00"),
                List.of(candidate(UUID.randomUUID(), List.of("translation"), "10.00", "90.00", 5, 0, 0))))
                .isEmpty();
    }

    @Test
    void filtersOutCandidatesPricedAboveBudget() {
        UUID affordable = UUID.randomUUID();
        assertThat(greedy().selectOne(task("summarisation", "30.00"), List.of(
                base(UUID.randomUUID(), "40.00", "90.00", 0, 0),
                base(affordable, "25.00", "50.00", 0, 100))))
                .contains(affordable);
    }

    @Test
    void returnsEmptyOnEmptyOrNullInput() {
        assertThat(greedy().selectOne(task("summarisation", "30.00"), List.of())).isEmpty();
        assertThat(greedy().rank(task("summarisation", "30.00"), List.of())).isEmpty();
        assertThat(greedy().rank(null, List.of())).isEmpty();
    }

    // ---- spec test 7: deterministic total tie-break ----

    @Test
    void tieBreaksByPriceThenVersionId() {
        UUID low = new UUID(0L, 1L);
        UUID high = new UUID(0L, 2L);
        // identical scores: same price/rep/load/samples -> tie on price too -> versionId ascending
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                candidate(high, List.of("summarisation"), "10.00", "50.00", 5, 0, 0),
                candidate(low, List.of("summarisation"), "10.00", "50.00", 5, 0, 0)));
        assertThat(ranked.get(0).candidate().agentVersionId()).isEqualTo(low);
        assertThat(ranked.get(1).candidate().agentVersionId()).isEqualTo(high);

        // and cheaper wins before id is consulted
        UUID cheap = UUID.randomUUID();
        List<ScoredCandidate> ranked2 = greedy().rank(task("summarisation", "40.00"), List.of(
                candidate(UUID.randomUUID(), List.of("summarisation"), "20.00", "50.00", 5, 0, 100),
                candidate(cheap, List.of("summarisation"), "16.00", "45.00", 5, 0, 100)));
        // scores engineered equal (budget 40, same load/exploration):
        // c1 = .4*.50 + .2*((40-20)/40) + .2*1.0 + e = .20 + .10 + .20 + e
        // c2 = .4*.45 + .2*((40-16)/40) + .2*1.0 + e = .18 + .12 + .20 + e   — equal sums
        assertThat(ranked2.get(0).candidate().agentVersionId()).isEqualTo(cheap);
    }

    // ---- spec test 8: capacity edge cases ----

    @Test
    void atAndOverCapacityClampToZeroButStayEligible() {
        UUID at = UUID.randomUUID();
        UUID over = UUID.randomUUID();
        List<ScoredCandidate> ranked = greedy().rank(task("summarisation", "30.00"), List.of(
                base(at, "10.00", "50.00", 5, 10),
                base(over, "10.00", "50.00", 7, 10)));
        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).score()).isCloseTo(ranked.get(1).score(), offset(1e-9));
    }

    // ---- spec test 9: price == budget ----

    @Test
    void priceEqualToBudgetIsEligibleWithZeroValueFit() {
        UUID only = UUID.randomUUID();
        assertThat(greedy().selectOne(task("summarisation", "30.00"),
                List.of(base(only, "30.00", "50.00", 0, 0)))).contains(only);
    }

    // ---- spec test 10: fixed-seed exploration sampling ----

    @Test
    void epsilonOneSamplesWeightedTowardUnderSampledAgents() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.0), new Random(7));
        UUID newcomer = UUID.randomUUID();
        UUID veteran = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                base(veteran, "10.00", "90.00", 0, 99),   // exploration weight 1/100
                base(newcomer, "10.00", "50.00", 0, 0));  // exploration weight 1
        int newcomerWins = 0;
        for (int i = 0; i < 200; i++) {
            if (service.selectOne(task("summarisation", "30.00"), candidates).orElseThrow().equals(newcomer)) {
                newcomerWins++;
            }
        }
        // weights 1 vs 0.0099 -> newcomer expected ~99% of draws; assert a wide, stable margin
        assertThat(newcomerWins).isGreaterThan(150);
    }

    // ---- spec test 11: single candidate always chosen ----

    @Test
    void singleCandidateAlwaysChosenRegardlessOfEpsilon() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(
                new MatchingPolicy(0.40, 0.20, 0.20, 0.20, 1.0), new Random(3));
        UUID only = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            assertThat(service.selectOne(task("summarisation", "30.00"),
                    List.of(base(only, "10.00", "50.00", 0, 0)))).contains(only);
        }
    }

    // ---- epsilon behaviour: greedy branch dominates at epsilon 0.1 with fixed seed ----

    @Test
    void greedyBranchTakesTopRankMostOfTheTime() {
        RoutingMatchDomainService service = new RoutingMatchDomainServiceImpl(POLICY, new Random(11));
        UUID top = UUID.randomUUID();
        List<AgentCandidate> candidates = List.of(
                base(top, "10.00", "90.00", 0, 0),
                base(UUID.randomUUID(), "25.00", "50.00", 4, 100));
        int topWins = 0;
        for (int i = 0; i < 200; i++) {
            if (service.selectOne(task("summarisation", "30.00"), candidates).orElseThrow().equals(top)) {
                topWins++;
            }
        }
        assertThat(topWins).isGreaterThan(160); // ~90% greedy + top is also the likely exploration pick
    }
}
