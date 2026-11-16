package com.hireai.domain.biz.task.sampling;

import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.enums.StageVerdict;
import com.hireai.domain.biz.task.sampling.model.SampleSizeTable;
import com.hireai.domain.biz.task.sampling.model.SamplingPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lot-size → sample-size table (spec S8, D21): ISO 2859-1 Table I code letters, then Table III-A
 * double sampling plans at the platform's AQL 2.5 (normal inspection). Expected values are copied from
 * the published tables, not computed.
 */
class SampleSizeTableTest {

    @ParameterizedTest(name = "lot {0} at level {1} -> letter {2}")
    @CsvSource({
            "2, II, A", "8, II, A", "9, II, B", "15, II, B", "16, II, C", "25, II, C", "26, II, D", "50, II, D",
            "51, II, E", "90, II, E", "91, II, F", "150, II, F", "151, II, G", "280, II, G", "281, II, H",
            "500, II, H", "501, II, J", "1000, II, J", "1200, II, J", "1201, II, K", "3200, II, K",
            "500, I, F", "500, III, J", "1000, III, K", "50, III, E", "8, III, B", "8, I, A",
            "10001, II, M", "500001, III, R"
    })
    void codeLetterFollowsTableI(int lotSize, InspectionLevel level, char letter) {
        assertThat(SampleSizeTable.codeLetter(lotSize, level)).isEqualTo(letter);
    }

    @ParameterizedTest(name = "lot {0} at level {1}: {2} then {3}, Ac1 {4} Re1 {5}, Ac2 {6} Re2 {7}")
    @CsvSource({
            // letter H, AQL 2.5 double: n=32+32, 1/3, 4/5 (single plan would be 50, 3/4)
            "500, II, 32, 32, 1, 3, 4, 5",
            // letter J: 50+50, 2/5, 6/7
            "1000, II, 50, 50, 2, 5, 6, 7",
            "500, III, 50, 50, 2, 5, 6, 7",
            // letter K: 80+80, 3/6, 9/10
            "1000, III, 80, 80, 3, 6, 9, 10",
            // letter L: 125+125, 5/9, 12/13
            "5000, II, 125, 125, 5, 9, 12, 13",
            // letter M: 200+200, 7/11, 18/19
            "20000, II, 200, 200, 7, 11, 18, 19",
            // letter N: 315+315, 11/16, 26/27
            "100000, II, 315, 315, 11, 16, 26, 27",
            // letter G: 20+20, 0/3, 3/4
            "200, II, 20, 20, 0, 3, 3, 4",
            // letter F: 13+13, 0/2, 1/2
            "100, II, 13, 13, 0, 2, 1, 2",
            "500, I, 13, 13, 0, 2, 1, 2",
            // letter E: 8+8, 0/2, 1/2
            "60, II, 8, 8, 0, 2, 1, 2"
    })
    void doublePlansFollowTableIIIA(int lotSize, InspectionLevel level, int first, int second,
                                    int ac1, int re1, int ac2, int re2) {
        SamplingPlan plan = SampleSizeTable.plan(lotSize, level);
        assertThat(plan.firstSampleSize()).isEqualTo(first);
        assertThat(plan.secondSampleSize()).isEqualTo(second);
        assertThat(plan.firstAccept()).isEqualTo(ac1);
        assertThat(plan.firstReject()).isEqualTo(re1);
        assertThat(plan.secondAccept()).isEqualTo(ac2);
        assertThat(plan.secondReject()).isEqualTo(re2);
        assertThat(plan.isDouble()).isTrue();
    }

    @Test
    void whereTheTableHasNoDoublePlanTheSingleZeroAcceptancePlanIsUsed() {
        // Lot 26-50 at level II is letter D: single n=8, Ac 0 / Re 1, and Table III-A has no double plan.
        SamplingPlan plan = SampleSizeTable.plan(40, InspectionLevel.II);
        assertThat(plan.codeLetter()).isEqualTo('D');
        assertThat(plan.isDouble()).isFalse();
        assertThat(plan.firstSampleSize()).isEqualTo(8);
        assertThat(plan.firstAccept()).isZero();
        assertThat(plan.firstReject()).isEqualTo(1);
    }

    @Test
    void lettersAboveTheAqlColumnUseThePlanBelowTheArrow() {
        // Letters A-C have no plan at AQL 2.5: follow the arrow down to D (n=8).
        assertThat(SampleSizeTable.plan(20, InspectionLevel.II).codeLetter()).isEqualTo('D');
    }

    @Test
    void aSampleAsLargeAsTheLotInspectsEveryItem() {
        SamplingPlan plan = SampleSizeTable.plan(5, InspectionLevel.II);
        assertThat(plan.fullInspection()).isTrue();
        assertThat(plan.firstSampleSize()).isEqualTo(5);
        assertThat(plan.isDouble()).isFalse();
    }

    @Test
    void aStricterLevelNeverSamplesFewerItems() {
        for (int lot = 2; lot <= 1000; lot++) {
            int normal = SampleSizeTable.plan(lot, InspectionLevel.II).firstSampleSize();
            int tightened = SampleSizeTable.plan(lot, InspectionLevel.III).firstSampleSize();
            assertThat(tightened).as("lot %d", lot).isGreaterThanOrEqualTo(normal);
        }
    }

    @ParameterizedTest(name = "{0} defects in the first sample of 32 -> {1}")
    @CsvSource({"0, ACCEPT", "1, ACCEPT", "2, SECOND_SAMPLE", "3, REJECT", "9, REJECT"})
    void firstSampleVerdict(int defects, StageVerdict expected) {
        assertThat(SampleSizeTable.plan(500, InspectionLevel.II).judgeFirst(defects)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} defects over both samples of 32 -> {1}")
    @CsvSource({"2, ACCEPT", "4, ACCEPT", "5, REJECT", "12, REJECT"})
    void secondSampleVerdictCountsBothSamples(int cumulativeDefects, StageVerdict expected) {
        assertThat(SampleSizeTable.plan(500, InspectionLevel.II).judgeSecond(cumulativeDefects)).isEqualTo(expected);
    }

    @Test
    void aSinglePlanAcceptsOrRejectsOnItsOwn() {
        SamplingPlan plan = SampleSizeTable.plan(40, InspectionLevel.II);
        assertThat(plan.judgeFirst(0)).isEqualTo(StageVerdict.ACCEPT);
        assertThat(plan.judgeFirst(1)).isEqualTo(StageVerdict.REJECT);
    }

    @Test
    void everyDoublePlanIsOneOfTheStandardsFixedAcceptanceSequences() {
        // ISO 2859-1 Table III-A pairs each first-stage Ac/Re with exactly one second-stage Ac/Re.
        Map<String, String> standard = Map.of("0/2", "1/2", "0/3", "3/4", "1/3", "4/5", "2/5", "6/7",
                "3/6", "9/10", "5/9", "12/13", "7/11", "18/19", "11/16", "26/27");
        for (int lot = 2; lot <= 600_000; lot = lot < 2000 ? lot + 1 : lot + 997) {
            for (InspectionLevel level : InspectionLevel.values()) {
                SamplingPlan plan = SampleSizeTable.plan(lot, level);
                if (!plan.isDouble()) {
                    continue;
                }
                String first = plan.firstAccept() + "/" + plan.firstReject();
                assertThat(plan.secondAccept() + "/" + plan.secondReject()).as("lot %d %s (%s)", lot, level, first)
                        .isEqualTo(standard.get(first));
            }
        }
    }
}
