package com.hireai.infrastructure.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The production {@link HostResolver}: the system DNS resolver. */
@Configuration
public class HostResolverConfig {

    @Bean
    public HostResolver hostResolver() {
        return HostResolver.SYSTEM;
    }
}
