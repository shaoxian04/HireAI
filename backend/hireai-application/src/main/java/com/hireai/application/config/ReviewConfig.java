package com.hireai.application.config;

import com.hireai.domain.biz.task.model.ReviewWindow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Reads {@code hireai.review.window} once, as the {@link ReviewWindow}. A non-positive value fails startup. */
@Configuration
public class ReviewConfig {

    @Bean
    public ReviewWindow reviewWindow(@Value("${hireai.review.window}") Duration length) {
        return new ReviewWindow(length);
    }
}
