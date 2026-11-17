package com.hireai.domain.biz.acceptance.aireview.model;

import com.hireai.domain.biz.acceptance.template.enums.OutputShape;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * How an AI Review is priced and when it is offered (spec S10, D37, D41). The fee is estimated at
 * submit from the Item's type and size, as cost plus margin: {@code (baseFee + perCriterionFee × n)},
 * times {@code mediaMultiplier} when the deliverable is a file (a multimodal call over the frozen
 * copy costs more than reading text). It is offered only when the Agent's Offer flags AI Review and
 * the fee is at most {@code maxFeeShare} (10%) of the Item price, so a Client never pays more to check
 * the work than the work is worth.
 */
public record AiReviewPolicy(Money baseFee, Money perCriterionFee, BigDecimal mediaMultiplier,
                             BigDecimal maxFeeShare, int maxCriteria) {

    public AiReviewPolicy {
        if (baseFee == null || baseFee.isNegative() || perCriterionFee == null || perCriterionFee.isNegative()
                || !baseFee.add(perCriterionFee).isPositive()) {
            throw new IllegalArgumentException("AI Review fees must be non-negative and not both zero");
        }
        if (mediaMultiplier == null || mediaMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("hireai.ai-review.media-multiplier must be at least 1");
        }
        if (maxFeeShare == null || maxFeeShare.signum() <= 0 || maxFeeShare.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("hireai.ai-review.max-fee-share must be in (0, 1]");
        }
        if (maxCriteria < 1) {
            throw new IllegalArgumentException("hireai.ai-review.max-criteria must be at least 1");
        }
    }

    /** The fee for {@code criteria} criteria on a deliverable of {@code shape}. */
    public Money feeFor(OutputShape shape, int criteria) {
        Money cost = baseFee;
        for (int i = 0; i < criteria; i++) {
            cost = cost.add(perCriterionFee);
        }
        return shape == OutputShape.FILE ? cost.percentage(mediaMultiplier) : cost;
    }

    /** The most an AI Review may cost on an Item of {@code itemPrice}, rounded down to the cent. */
    public Money maxFeeFor(Money itemPrice) {
        return Money.of(itemPrice.value().multiply(maxFeeShare).setScale(2, RoundingMode.DOWN));
    }

    /** What a booking would be quoted; never throws, so the booking screen can say why it is not offered. */
    public AiReviewQuote quote(OutputShape shape, int criteria, Money itemPrice, boolean offerAccepts) {
        Money fee = feeFor(shape, Math.max(1, criteria));
        Money maxFee = maxFeeFor(itemPrice);
        String reason = refusal(fee, maxFee, offerAccepts);
        return new AiReviewQuote(fee, maxFee, reason == null, reason);
    }

    /** The AI Review a booking freezes into its Contract; refused (400) when it is not offered. */
    public AiReviewTerms termsFor(OutputShape shape, List<String> criteria, Money itemPrice, boolean offerAccepts) {
        if (criteria == null || criteria.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "AI Review needs at least one criterion");
        }
        if (criteria.size() > maxCriteria) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "AI Review takes at most " + maxCriteria + " criteria");
        }
        Money fee = feeFor(shape, criteria.size());
        String reason = refusal(fee, maxFeeFor(itemPrice), offerAccepts);
        if (reason != null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, reason);
        }
        return new AiReviewTerms(criteria, fee);
    }

    private String refusal(Money fee, Money maxFee, boolean offerAccepts) {
        if (!offerAccepts) {
            return "This Agent's Offer does not accept AI Review";
        }
        if (fee.isGreaterThan(maxFee)) {
            String share = maxFeeShare.movePointRight(2).stripTrailingZeros().toPlainString();
            return "AI Review would cost " + fee + ", more than " + share + "% of the Item price (" + maxFee
                    + "), so it is not offered";
        }
        return null;
    }
}
