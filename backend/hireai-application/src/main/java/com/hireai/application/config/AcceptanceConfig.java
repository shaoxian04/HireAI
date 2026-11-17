package com.hireai.application.config;

import com.hireai.domain.biz.acceptance.template.model.TemplateTransition;
import com.hireai.domain.biz.task.item.model.FlaggedShareCap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Reads {@code hireai.acceptance.template-transition} once as the {@link TemplateTransition} (spec D36), and
 * {@code hireai.acceptance.flagged-share} / {@code flagged-minimum} as the {@link FlaggedShareCap}.
 * A value out of range fails startup.
 */
@Configuration
public class AcceptanceConfig {

    @Bean
    public TemplateTransition templateTransition(
            @Value("${hireai.acceptance.template-transition:P30D}") Duration period) {
        return new TemplateTransition(period);
    }

    @Bean
    public FlaggedShareCap flaggedShareCap(
            @Value("${hireai.acceptance.flagged-share:0.10}") BigDecimal share,
            @Value("${hireai.acceptance.flagged-minimum:1}") int minimum) {
        return new FlaggedShareCap(share, minimum);
    }
}
