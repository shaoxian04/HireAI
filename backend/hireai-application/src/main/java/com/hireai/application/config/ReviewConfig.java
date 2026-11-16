package com.hireai.application.config;

import com.hireai.domain.biz.adjudication.model.ReworkPolicy;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.rejection.model.RejectionWindows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Reads the review-side windows once: {@code hireai.review.window} as the {@link ReviewWindow}, and
 * {@code hireai.rejection.*} as the {@link RejectionWindows} (#95). A non-positive value fails startup.
 */
@Configuration
public class ReviewConfig {

    @Bean
    public ReviewWindow reviewWindow(@Value("${hireai.review.window}") Duration length) {
        return new ReviewWindow(length);
    }

    /** {@code hireai.acceptance.max-reworks}: how often a rejected result goes back to its Agent (D17, D41). */
    @Bean
    public ReworkPolicy reworkPolicy(@Value("${hireai.acceptance.max-reworks:2}") int maxReworks) {
        return new ReworkPolicy(maxReworks);
    }

    @Bean
    public RejectionWindows rejectionWindows(
            @Value("${hireai.rejection.agent-response-window}") Duration agentResponse,
            @Value("${hireai.rejection.client-decision-window}") Duration clientDecision) {
        return new RejectionWindows(agentResponse, clientDecision);
    }
}
