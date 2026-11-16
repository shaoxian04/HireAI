package com.hireai.application.config;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewPolicy;
import com.hireai.domain.shared.model.Money;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/** Reads {@code hireai.ai-review.*} once (spec S10, D37, D41); an invalid value fails startup. */
@Configuration
public class AiReviewConfig {

    @Bean
    public AiReviewPolicy aiReviewPolicy(
            @Value("${hireai.ai-review.base-fee:0.10}") BigDecimal baseFee,
            @Value("${hireai.ai-review.fee-per-criterion:0.10}") BigDecimal feePerCriterion,
            @Value("${hireai.ai-review.media-multiplier:2}") BigDecimal mediaMultiplier,
            @Value("${hireai.ai-review.max-fee-share:0.10}") BigDecimal maxFeeShare,
            @Value("${hireai.ai-review.max-criteria:5}") int maxCriteria) {
        return new AiReviewPolicy(Money.of(baseFee), Money.of(feePerCriterion), mediaMultiplier, maxFeeShare,
                maxCriteria);
    }

    @Bean
    public AiReviewSettings aiReviewSettings(
            @Value("${hireai.ai-review.max-attempts:3}") int maxAttempts,
            @Value("${hireai.ai-review.retry-after:PT2M}") Duration retryAfter) {
        return new AiReviewSettings(maxAttempts, retryAfter);
    }
}
