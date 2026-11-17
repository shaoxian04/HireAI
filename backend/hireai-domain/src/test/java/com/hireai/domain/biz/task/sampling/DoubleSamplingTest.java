package com.hireai.domain.biz.task.sampling;

import com.hireai.domain.biz.ledger.settlement.info.SettlementBreakdown;
import com.hireai.domain.biz.ledger.settlement.service.impl.SettlementDomainServiceImpl;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.enums.SampleItemOutcome;
import com.hireai.domain.biz.task.sampling.enums.InspectionStatus;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ProRata;
import com.hireai.domain.biz.task.sampling.model.SampleSizeTable;
import com.hireai.domain.biz.task.sampling.model.SampleTally;
import com.hireai.domain.biz.task.sampling.model.SamplingDecision;
import com.hireai.domain.biz.task.sampling.model.SamplingPlan;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D19: a failed sample draws a second sample; failing that, the whole Task is reworked once and
 * resampled; failing again, it settles pro rata from the random sample's measured error rate.
 */
class DoubleSamplingTest {

    @Test
    void anInspectionWithNothingLeftToSampleClosesAndAFinishedOneStaysAsItEnded() {
        InspectionModel open = InspectionModel.open(UUID.randomUUID(), InspectionLevel.DEFAULT);

        assertThat(open.close().status()).isEqualTo(InspectionStatus.CLOSED);
        assertThat(open.close().status().isFinished()).isTrue();
        assertThat(open.startRework().close().status()).isEqualTo(InspectionStatus.CLOSED);
        assertThat(open.acceptLot().close().status()).isEqualTo(InspectionStatus.ACCEPTED);
    }


    private static final SamplingPlan PLAN_500 = SampleSizeTable.plan(500, InspectionLevel.II); // 32+32, 1/3, 4/5

    @Test
    void aCleanFirstSampleAcceptsTheLot() {
        assertThat(SamplingDecision.after(PLAN_500, 1, 1, 32, 1, 1)).isEqualTo(SamplingDecision.acceptLot());
    }

    @Test
    void anInconclusiveFirstSampleDrawsTheSecond() {
        assertThat(SamplingDecision.after(PLAN_500, 1, 2, 32, 2, 1)).isEqualTo(SamplingDecision.drawSecond());
    }

    @Test
    void aFailedSecondSampleInTheFirstRoundReworksTheWholeTask() {
        assertThat(SamplingDecision.after(PLAN_500, 2, 5, 64, 3, 1)).isEqualTo(SamplingDecision.rework());
    }

    @Test
    void aFirstSampleAtOrAboveTheRejectionNumberStillDrawsTheSecondSample() {
        // D19: a failed first sample always draws a second before any rework (the spec, not ISO's early reject).
        assertThat(SamplingDecision.after(PLAN_500, 1, 3, 32, 3, 1)).isEqualTo(SamplingDecision.drawSecond());
        assertThat(SamplingDecision.after(PLAN_500, 1, 9, 32, 9, 2)).isEqualTo(SamplingDecision.drawSecond());
    }

    @Test
    void aSecondSampleWithinTheCumulativeAcceptanceNumberAcceptsTheLot() {
        assertThat(SamplingDecision.after(PLAN_500, 2, 4, 64, 2, 1)).isEqualTo(SamplingDecision.acceptLot());
    }

    @Test
    void failingAgainAfterTheReworkSettlesProRataFromTheMeasuredErrorRate() {
        SamplingDecision decision = SamplingDecision.after(PLAN_500, 2, 6, 64, 3, 2);
        assertThat(decision.next()).isEqualTo(SamplingDecision.Next.PRO_RATA);
        assertThat(decision.errorRate()).isEqualByComparingTo("0.0938"); // 6 / 64 = 0.09375, half-up to 4dp
    }

    @Test
    void theReworkedTaskIsResampledAndCanStillPass() {
        assertThat(SamplingDecision.after(PLAN_500, 1, 0, 32, 0, 2)).isEqualTo(SamplingDecision.acceptLot());
    }

    @Test
    void onlyTheRandomSampleFeedsTheErrorRate() {
        Map<Integer, SampleItemOutcome> outcomes = new HashMap<>();
        for (int n = 1; n <= 10; n++) {
            outcomes.put(n, SampleItemOutcome.PASS);
        }
        outcomes.put(3, SampleItemOutcome.DEFECT);   // sampled, wrong
        outcomes.put(11, SampleItemOutcome.DEFECT);  // an extra check by the reviewer, outside the sample
        outcomes.put(12, SampleItemOutcome.DEFECT);
        SampleTally tally = SampleTally.of(List.of(1, 2, 3, 4, 5), outcomes);
        assertThat(tally.sampled()).isEqualTo(5);
        assertThat(tally.defects()).isEqualTo(1);
        assertThat(tally.complete()).isTrue();
    }

    @Test
    void aSampleIsNotJudgedWhileAnySampledItemIsStillOpen() {
        // An Item on its rejection response path (D17') counts once that path settles.
        Map<Integer, SampleItemOutcome> outcomes = Map.of(1, SampleItemOutcome.PASS, 2, SampleItemOutcome.OPEN);
        assertThat(SampleTally.of(List.of(1, 2), outcomes).complete()).isFalse();
    }

    @Test
    void aFailedSingleZeroAcceptanceSampleDrawsASecondSampleOfTheSameSizeJudgedOnItsOwn() {
        SamplingPlan single = SampleSizeTable.plan(40, InspectionLevel.II); // n=8, Ac 0 / Re 1
        assertThat(SamplingDecision.after(single, 1, 1, 8, 1, 1)).isEqualTo(SamplingDecision.drawSecond());
        assertThat(single.sampleSize(2)).isEqualTo(8);
        // A clean second sample accepts the lot; a second failure reworks it (round 1) or settles pro rata.
        assertThat(SamplingDecision.after(single, 2, 1, 16, 0, 1)).isEqualTo(SamplingDecision.acceptLot());
        assertThat(SamplingDecision.after(single, 2, 2, 16, 1, 1)).isEqualTo(SamplingDecision.rework());
        SamplingDecision last = SamplingDecision.after(single, 2, 2, 16, 1, 2);
        assertThat(last.next()).isEqualTo(SamplingDecision.Next.PRO_RATA);
        assertThat(last.errorRate()).isEqualByComparingTo("0.125");
    }

    @Test
    void whenTheFirstSampleWasTheWholeLotAFailureHasNoSecondSampleToDraw() {
        SamplingPlan whole = SampleSizeTable.plan(5, InspectionLevel.II); // every Item, Ac 0
        assertThat(SamplingDecision.afterWholeLot(whole, 1, 5, 1)).isEqualTo(SamplingDecision.rework());
        assertThat(SamplingDecision.afterWholeLot(whole, 0, 5, 1)).isEqualTo(SamplingDecision.acceptLot());
        assertThat(SamplingDecision.afterWholeLot(whole, 1, 5, 2).next()).isEqualTo(SamplingDecision.Next.PRO_RATA);
    }

    @Test
    void proRataPaysEachPassingItemItsPriceTimesOneMinusTheErrorRate() {
        // 6 wrong in 64 sampled: an unverified Item priced 4.00 is paid 4.00 x (1 - 0.0938) = 3.62.
        Money paid = ProRata.paidFor(Money.of("4.00"), new BigDecimal("0.0938"));
        assertThat(paid).isEqualTo(Money.of("3.62"));
        assertThat(ProRata.paidFor(Money.of("4.00"), BigDecimal.ONE)).isEqualTo(Money.ZERO);
        assertThat(ProRata.paidFor(Money.of("4.00"), BigDecimal.ZERO)).isEqualTo(Money.of("4.00"));
    }

    @Test
    void theErrorRateIsDefectsOverSampledItems() {
        assertThat(ProRata.errorRate(6, 64)).isEqualByComparingTo("0.0938");
        assertThat(ProRata.errorRate(0, 32)).isEqualByComparingTo("0");
    }

    @Test
    void aProRataSettlementReleasesExactlyTheItemsEscrow() {
        String[] prices = {"0.04", "0.07", "1.00", "3.33", "4.00", "9.99", "123.45"};
        String[] rates = {"0.0156", "0.0938", "0.3333", "0.5", "0.9999"};
        for (String p : prices) {
            for (String r : rates) {
                Money price = Money.of(p);
                UUID taskId = UUID.randomUUID();
                WalletModel client = WalletModel.openFor(UUID.randomUUID());
                WalletModel builder = WalletModel.openFor(UUID.randomUUID());
                client.topUp(price, "seed");
                client.freeze(price, taskId, "freeze");
                Money gross = ProRata.paidFor(price, new BigDecimal(r));
                SettlementDomainServiceImpl settlement = new SettlementDomainServiceImpl();
                SettlementBreakdown b;
                if (gross.isPositive()) {
                    b = settlement.settlePartial(client, builder, price, gross, taskId, "settle-" + taskId + "-item-1");
                } else { // nothing left to pay: the Item is refunded in full
                    settlement.settleRejection(client, price, taskId, "settle-" + taskId + "-item-1");
                    b = new SettlementBreakdown(Money.ZERO, Money.ZERO);
                }
                assertThat(b.net().add(b.commission())).as("%s at %s", p, r).isEqualTo(gross);
                assertThat(client.escrow()).as("%s at %s", p, r).isEqualTo(Money.ZERO);
                assertThat(client.available().add(builder.available()).add(b.commission())).isEqualTo(price);
            }
        }
    }

    @Test
    void anItemCountsAsPassOnceAcceptedAsADefectOnceRefundedAndOpenOtherwise() {
        assertThat(SampleItemOutcome.of(com.hireai.domain.biz.task.item.enums.ItemStatus.ACCEPTED))
                .isEqualTo(SampleItemOutcome.PASS);
        assertThat(SampleItemOutcome.of(com.hireai.domain.biz.task.item.enums.ItemStatus.CONCEDED))
                .isEqualTo(SampleItemOutcome.DEFECT);
        assertThat(SampleItemOutcome.of(com.hireai.domain.biz.task.item.enums.ItemStatus.SPEC_VIOLATION))
                .isEqualTo(SampleItemOutcome.DEFECT);
        assertThat(SampleItemOutcome.of(com.hireai.domain.biz.task.item.enums.ItemStatus.REJECTED))
                .isEqualTo(SampleItemOutcome.OPEN);
        assertThat(SampleItemOutcome.of(com.hireai.domain.biz.task.item.enums.ItemStatus.PENDING_REVIEW))
                .isEqualTo(SampleItemOutcome.OPEN);
    }
}
