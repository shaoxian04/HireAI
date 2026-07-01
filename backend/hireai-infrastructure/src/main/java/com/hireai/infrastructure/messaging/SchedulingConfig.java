package com.hireai.infrastructure.messaging;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled beans (the arbitration sweeper) outside the permissive test profile. */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
