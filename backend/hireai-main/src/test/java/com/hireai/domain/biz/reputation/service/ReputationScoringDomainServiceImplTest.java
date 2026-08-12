package com.hireai.domain.biz.reputation.service;

import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;
import com.hireai.domain.biz.reputation.service.impl.ReputationScoringDomainServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The scoring model from the design doc §5 and ADR 0003. Framework-free: no Spring, no database.
 * Most of Module 5's risk lives in this arithmetic, so it is pinned here rather than at a seam
 * that needs Docker to run.
 */
class ReputationScoringDomainServiceImplTest {

    private final ReputationScoringDomainService service =
            new ReputationScoringDomainServiceImpl(ReputationPolicy.defaults());

    /** quality of an n-star rating, per the design doc §6: (stars − 1) / 4. */
    private static BigDecimal stars(int n) {
        return BigDecimal.valueOf(n - 1).divide(BigDecimal.valueOf(4), 3, java.math.RoundingMode.HALF_UP);
    }

    private static ReputationAggregates aggregates(double relSum, long relCount,
                                                   BigDecimal satSum, long satCount) {
        return new ReputationAggregates(BigDecimal.valueOf(relSum), relCount, satSum, satCount);
    }

    // ---------------------------------------------------------------- the §5 table

    /**
     * The single most load-bearing number in the module: a brand-new agent must score EXACTLY the
     * 50.00 that AgentModel.DEFAULT_REPUTATION has written since V3, so no existing agent's routing
     * shifts on migration day.
     */
    @Test
    void aZeroEventAgentScoresExactlyFifty() {
        ReputationScore s = service.score(ReputationAggregates.empty());

        assertThat(s.score()).isEqualByComparingTo("50.00");
        assertThat(s.reliability()).isEqualByComparingTo("0.5");
        assertThat(s.satisfaction()).isEqualByComparingTo("0.5");
    }

    @Test
    void twentyAcceptsAndTwentyFourStarRatingsScoreEightyThree() {
        ReputationScore s = service.score(
                aggregates(20.0, 20, stars(4).multiply(BigDecimal.valueOf(20)), 20));

        assertThat(s.reliability().doubleValue()).isCloseTo(0.900, within(0.001));
        assertThat(s.satisfaction().doubleValue()).isCloseTo(0.667, within(0.001));
        assertThat(s.score().doubleValue()).isCloseTo(83.0, within(0.05));
    }

    @Test
    void twentyAcceptsWithNoRatingsScoreSeventyEight() {
        ReputationScore s = service.score(aggregates(20.0, 20, BigDecimal.ZERO, 0));

        assertThat(s.reliability().doubleValue()).isCloseTo(0.900, within(0.001));
        assertThat(s.satisfaction()).isEqualByComparingTo("0.5");
        assertThat(s.score().doubleValue()).isCloseTo(78.0, within(0.05));
    }

    @Test
    void twentyAcceptsAndTwentyOneStarRatingsScoreSixtyEight() {
        ReputationScore s = service.score(aggregates(20.0, 20, BigDecimal.ZERO, 20));

        assertThat(s.reliability().doubleValue()).isCloseTo(0.900, within(0.001));
        assertThat(s.satisfaction().doubleValue()).isCloseTo(0.167, within(0.001));
        assertThat(s.score().doubleValue()).isCloseTo(68.0, within(0.05));
    }

    @Test
    void oneHundredApiAutoSettlesCapAtEightyThreePointThree() {
        ReputationScore s = service.score(aggregates(100.0, 100, BigDecimal.ZERO, 0));

        assertThat(s.reliability().doubleValue()).isCloseTo(0.976, within(0.001));
        assertThat(s.satisfaction()).isEqualByComparingTo("0.5");
        assertThat(s.score().doubleValue()).isCloseTo(83.3, within(0.05));
    }

    @Test
    void oneHundredAcceptsAndOneHundredFiveStarRatingsScoreNinetySeven() {
        ReputationScore s = service.score(
                aggregates(100.0, 100, BigDecimal.valueOf(100), 100));

        assertThat(s.reliability().doubleValue()).isCloseTo(0.976, within(0.001));
        assertThat(s.satisfaction().doubleValue()).isCloseTo(0.955, within(0.001));
        assertThat(s.score().doubleValue()).isCloseTo(97.0, within(0.05));
    }

    // ------------------------------------------------- the properties the split exists to protect

    /**
     * The pathology that killed the single-stream model (ADR 0003, design §2): an agent that did
     * good work and earned 4★ must outrank an agent that did bad work nobody reviewed. Under both
     * candidate single-stream resolutions the bad agent won.
     */
    @Test
    void goodWorkWithFourStarsBeatsSilenceWhichBeatsBadReviews() {
        BigDecimal twentyFourStars = stars(4).multiply(BigDecimal.valueOf(20));

        BigDecimal reviewed = service.score(aggregates(20.0, 20, twentyFourStars, 20)).score();
        BigDecimal silent = service.score(aggregates(20.0, 20, BigDecimal.ZERO, 0)).score();
        BigDecimal panned = service.score(aggregates(20.0, 20, BigDecimal.ZERO, 20)).score();

        assertThat(reviewed).isGreaterThan(silent);
        assertThat(silent).isGreaterThan(panned);
    }

    /**
     * Silence must read as UNKNOWN, never as PERFECT. An unrated agent falls back to the neutral
     * prior — this is the property the whole two-component split exists to preserve, and the one a
     * refactor is most likely to quietly break.
     */
    @Test
    void anUnratedAgentSitsAtTheNeutralPriorNotAtTheCeiling() {
        ReputationScore flawlessButUnrated = service.score(aggregates(500.0, 500, BigDecimal.ZERO, 0));

        assertThat(flawlessButUnrated.satisfaction()).isEqualByComparingTo("0.5");
        assertThat(flawlessButUnrated.score()).isLessThan(BigDecimal.valueOf(85));
    }

    /**
     * Volume enters only through the denominator, as confidence: it moves an agent toward its true
     * quality rate and can never carry it past that rate.
     */
    @Test
    void volumeConvergesTowardTheTrueRateWithoutEverPassingIt() {
        double trueRate = 0.8;
        double previous = 0.0;

        for (long n : new long[]{10, 100, 1_000, 10_000}) {
            double reliability = service.score(
                    aggregates(trueRate * n, n, BigDecimal.ZERO, 0)).reliability().doubleValue();

            assertThat(reliability).isLessThan(trueRate);      // never past the true rate
            assertThat(reliability).isGreaterThan(previous);   // monotonically toward it
            previous = reliability;
        }
        assertThat(previous).isCloseTo(trueRate, within(0.001));
    }

    /**
     * A prolific mediocre agent must not outrank a rare flawless one — the failure mode that sank
     * the points-balance model (ADR 0003).
     */
    @Test
    void aProlificMediocreAgentDoesNotOutrankARareExcellentOne() {
        BigDecimal prolificMediocre = service.score(aggregates(300.0, 500, BigDecimal.ZERO, 0)).score();
        BigDecimal rareFlawless = service.score(aggregates(10.0, 10, BigDecimal.ZERO, 0)).score();

        assertThat(rareFlawless).isGreaterThan(prolificMediocre);
    }

    @Test
    void scoreIsMonotonicInOutcomeQuality() {
        BigDecimal worse = service.score(aggregates(5.0, 10, BigDecimal.ZERO, 0)).score();
        BigDecimal better = service.score(aggregates(9.0, 10, BigDecimal.ZERO, 0)).score();

        assertThat(better).isGreaterThan(worse);
    }

    // ------------------------------------------------------------- recovery (why no decay is needed)

    /**
     * Design doc §5: recovery is earned by working, which is why the model needs no time decay —
     * decay would add a second recovery path (hibernation) and let a bad agent launder its record.
     */
    @Test
    void reliabilityRecoversThroughWorkAlone() {
        double afterFailures = service.score(aggregates(0.0, 10, BigDecimal.ZERO, 0))
                .reliability().doubleValue();
        double after20Successes = service.score(aggregates(20.0, 30, BigDecimal.ZERO, 0))
                .reliability().doubleValue();
        double after200Successes = service.score(aggregates(200.0, 210, BigDecimal.ZERO, 0))
                .reliability().doubleValue();

        assertThat(afterFailures).isCloseTo(0.167, within(0.001));
        assertThat(after20Successes).isCloseTo(0.643, within(0.001));
        assertThat(after200Successes).isCloseTo(0.942, within(0.001));
    }

    // ------------------------------------------------------------------------------ the blend

    /**
     * α = 0.7 is a tuning constant, not a law: the blend must actually come from the policy, so the
     * marketplace can be retuned from configuration without a migration.
     */
    @Test
    void theBlendFactorComesFromThePolicy() {
        ReputationAggregates perfectWorkPannedByClients = aggregates(20.0, 20, BigDecimal.ZERO, 20);

        BigDecimal underDefault = service.score(perfectWorkPannedByClients).score();
        BigDecimal underRatingsHeavy = new ReputationScoringDomainServiceImpl(
                new ReputationPolicy(0.3, 5.0, 10.0, 0.5)).score(perfectWorkPannedByClients).score();

        assertThat(underRatingsHeavy).isLessThan(underDefault);
    }

    /**
     * kS = 10 vs kR = 5 holds the forgeable signal to a higher evidential bar: the same number of
     * samples moves Satisfaction less far from the prior than it moves Reliability.
     */
    @Test
    void satisfactionIsHeldToAHigherEvidentialBarThanReliability() {
        ReputationScore s = service.score(aggregates(10.0, 10, BigDecimal.valueOf(10), 10));

        double reliabilityDistance = s.reliability().doubleValue() - 0.5;
        double satisfactionDistance = s.satisfaction().doubleValue() - 0.5;

        assertThat(satisfactionDistance).isLessThan(reliabilityDistance);
    }

    @Test
    void scoreIsRoundedToTheTwoDecimalPlacesTheColumnStores() {
        assertThat(service.score(aggregates(7.0, 9, BigDecimal.ZERO, 3)).score().scale())
                .isEqualTo(2);
    }
}
