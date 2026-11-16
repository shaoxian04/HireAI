package com.hireai.application.config;

import com.hireai.domain.biz.acceptance.template.model.TemplateTransition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Reads {@code hireai.acceptance.template-transition} once as the {@link TemplateTransition} (spec D36).
 * A negative value fails startup.
 */
@Configuration
public class AcceptanceConfig {

    @Bean
    public TemplateTransition templateTransition(
            @Value("${hireai.acceptance.template-transition:P30D}") Duration period) {
        return new TemplateTransition(period);
    }
}
