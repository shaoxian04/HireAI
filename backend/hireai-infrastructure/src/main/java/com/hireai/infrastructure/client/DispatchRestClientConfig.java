package com.hireai.infrastructure.client;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

/**
 * Applies bounded connect/read timeouts to ALL Spring RestClient.Builder instances —
 * including {@link AgentDispatchClient} and {@link SupabaseStorageClient} — so that a slow or
 * unresponsive remote endpoint cannot block a thread indefinitely. Implemented as a
 * {@link RestClientCustomizer} rather than a per-instance {@code requestFactory(...)} override
 * so that a test's {@code MockRestServiceServer}, which installs its own factory on a plain
 * {@code RestClient.builder()}, is never clobbered.
 */
@Configuration
public class DispatchRestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClientCustomizer outboundRestClientTimeoutCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        return builder -> builder.requestFactory(requestFactory);
    }
}
