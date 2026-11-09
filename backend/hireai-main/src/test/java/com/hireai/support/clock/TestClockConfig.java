package com.hireai.support.clock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * Replaces the app's {@code Clock} bean with a {@link MutableTestClock} that starts at the real
 * current time. {@code @Import} it into a whole-app test, autowire {@link MutableTestClock}, and
 * move time before calling a sweeper's application service (sweepers are off in the test profile).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    @Primary
    public MutableTestClock mutableTestClock() {
        return new MutableTestClock(Instant.now());
    }
}
