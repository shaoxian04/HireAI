package com.hireai.support.dns;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the system DNS resolver in every Spring test context (picked up by component scanning,
 * like {@code TestDispatchTokenServiceConfig}), so Agent and client webhook URLs such as
 * {@code https://agent.example.com/hook} pass the SSRF guard without network access. Lives under
 * {@code src/test} only.
 */
@Configuration
public class TestHostResolverConfig {

    @Bean
    @Primary
    public TestHostResolver testHostResolver() {
        return new TestHostResolver();
    }
}
