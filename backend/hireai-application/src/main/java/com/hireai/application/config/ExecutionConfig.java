package com.hireai.application.config;

import com.hireai.domain.biz.task.model.ExecutionGrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reads {@code hireai.execution.grace-seconds} once, as the {@link ExecutionGrace} every execution
 * deadline, client-deadline fit and dispatch retry budget uses. A negative value fails startup.
 */
@Configuration
public class ExecutionConfig {

    @Bean
    public ExecutionGrace executionGrace(@Value("${hireai.execution.grace-seconds}") long graceSeconds) {
        return new ExecutionGrace(graceSeconds);
    }
}
