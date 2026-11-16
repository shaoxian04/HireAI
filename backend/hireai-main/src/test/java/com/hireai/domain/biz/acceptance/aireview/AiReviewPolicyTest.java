package com.hireai.domain.biz.acceptance.aireview;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewPolicy;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewQuote;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewTerms;
import com.hireai.domain.biz.acceptance.aireview.model.CriterionVerdict;
import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec S10 (D5, D37): the AI Review fee, its 10% gate and how a verdict maps to a ruling category. */
class AiReviewPolicyTest {

    /** 0.10 base + 0.10 per criterion, a file costs twice as much, offered up to 10% of the Item price. */
    private final AiReviewPolicy policy = new AiReviewPolicy(Money.of("0.10"), Money.of("0.10"),
            new BigDecimal("2"), new BigDecimal("0.10"), 5);

    @Test
    void theFeeGrowsWithTheCriteriaAndDoublesForAFile() {
        assertThat(policy.feeFor(OutputShape.TEXT, 1)).isEqualTo(Money.of("0.20"));
        assertThat(policy.feeFor(OutputShape.JSON, 3)).isEqualTo(Money.of("0.40"));
        assertThat(policy.feeFor(OutputShape.FILE, 2)).isEqualTo(Money.of("0.60"));
    }

    @Test
    void itIsOfferedOnlyWhenTheFeeIsAtMostTenPercentOfTheItemPrice() {
        AiReviewQuote atTheGate = policy.quote(OutputShape.TEXT, 2, Money.of("3.00"), true);
        assertThat(atTheGate.available()).isTrue();
        assertThat(atTheGate.fee()).isEqualTo(Money.of("0.30"));
        assertThat(atTheGate.maxFee()).isEqualTo(Money.of("0.30"));

        AiReviewQuote over = policy.quote(OutputShape.TEXT, 2, Money.of("2.99"), true);
        assertThat(over.available()).isFalse();
        assertThat(over.reason()).contains("10%").contains("0.30");
    }

    @Test
    void itIsNotOfferedWhenTheOfferDoesNotFlagIt() {
        AiReviewQuote quote = policy.quote(OutputShape.TEXT, 1, Money.of("100.00"), false);
        assertThat(quote.available()).isFalse();
        assertThat(quote.reason()).contains("does not accept AI Review");
    }

    @Test
    void termsAreRefusedOutsideTheGateAndKeepTheirFrozenFee() {
        AiReviewTerms terms = policy.termsFor(OutputShape.TEXT, List.of("Polite tone", "Mentions the refund"),
                Money.of("10.00"), true);
        assertThat(terms.fee()).isEqualTo(Money.of("0.30"));
        assertThat(terms.criteria()).containsExactly("Polite tone", "Mentions the refund");

        assertThatThrownBy(() -> policy.termsFor(OutputShape.FILE, List.of("a", "b", "c"), Money.of("5.00"), true))
                .isInstanceOf(DomainException.class).hasMessageContaining("10%");
        assertThatThrownBy(() -> policy.termsFor(OutputShape.TEXT, List.of("a"), Money.of("10.00"), false))
                .isInstanceOf(DomainException.class).hasMessageContaining("does not accept AI Review");
        assertThatThrownBy(() -> policy.termsFor(OutputShape.TEXT, List.of("1", "2", "3", "4", "5", "6"),
                Money.of("100.00"), true)).isInstanceOf(DomainException.class).hasMessageContaining("at most 5");
        assertThatThrownBy(() -> policy.termsFor(OutputShape.TEXT, List.of(" "), Money.of("100.00"), true))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void everyCriterionPassingIsFulfilledSomeIsPartialNoneIsNotFulfilled() {
        assertThat(completed(true, true).rulingCategory()).isEqualTo(RulingCategory.FULFILLED);
        assertThat(completed(true, false).rulingCategory()).isEqualTo(RulingCategory.PARTIALLY_FULFILLED);
        assertThat(completed(false, false).rulingCategory()).isEqualTo(RulingCategory.NOT_FULFILLED);
    }

    @Test
    void aVerdictMustJudgeEveryFrozenCriterionExactlyOnce() {
        AiReviewModel requested = requested(List.of("a", "b"));
        assertThatThrownBy(() -> requested.complete(List.of(new CriterionVerdict("a", true, "ok")), "m", Instant.now()))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> requested.complete(List.of(new CriterionVerdict("a", true, "ok"),
                new CriterionVerdict("c", true, "ok")), "m", Instant.now())).isInstanceOf(DomainException.class);
    }

    @Test
    void aReviewThatFailsTooOftenGivesUpAndOnlyAFrozenOneIsReleased() {
        AiReviewModel review = requested(List.of("a"));
        AiReviewModel once = review.recordFailure("timeout", 2, Instant.now());
        assertThat(once.status().name()).isEqualTo("REQUESTED");
        AiReviewModel twice = once.recordFailure("timeout", 2, Instant.now());
        assertThat(twice.status().name()).isEqualTo("FAILED");
        assertThatThrownBy(() -> twice.release(Instant.now())).isInstanceOf(DomainException.class);
        assertThat(AiReviewModel.frozen(UUID.randomUUID(), new AiReviewTerms(List.of("a"), Money.of("0.20")),
                Instant.now()).release(Instant.now()).status().name()).isEqualTo("RELEASED");
    }

    private AiReviewModel completed(boolean first, boolean second) {
        return requested(List.of("a", "b")).complete(List.of(new CriterionVerdict("b", second, "why b"),
                new CriterionVerdict("a", first, "why a")), "test-model", Instant.now());
    }

    private static AiReviewModel requested(List<String> criteria) {
        return AiReviewModel.frozen(UUID.randomUUID(), new AiReviewTerms(criteria, Money.of("0.30")), Instant.now())
                .request(false, Instant.now());
    }
}
