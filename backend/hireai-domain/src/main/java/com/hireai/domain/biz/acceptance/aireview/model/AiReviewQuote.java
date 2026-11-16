package com.hireai.domain.biz.acceptance.aireview.model;

import com.hireai.domain.shared.model.Money;

/**
 * What an AI Review would cost on a booking, and whether it is offered (spec D37): {@code maxFee} is
 * the gate (10% of the Item price); {@code reason} says why it is not offered, null when it is.
 */
public record AiReviewQuote(Money fee, Money maxFee, boolean available, String reason) {
}
