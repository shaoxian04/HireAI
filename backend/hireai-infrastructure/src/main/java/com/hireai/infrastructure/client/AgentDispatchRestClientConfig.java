package com.hireai.infrastructure.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The dedicated {@link RestClient} that sends WorkOrders to Agent webhooks. It is built on the JDK
 * {@link HttpClient} rather than the shared Apache client, for two load-bearing reasons:
 * <ul>
 *   <li><b>Redirects are never followed</b> ({@link HttpClient.Redirect#NEVER}). The SSRF guard
 *       only checks the registered URL, so a public URL answering 3xx towards an internal host must
 *       not be followed (Hard Invariant #6). {@link AgentDispatchClient} treats a 3xx as a failed send.</li>
 *   <li><b>No automatic retries.</b> The Apache client retries a 429/503 POST once by itself, so the
 *       Agent received a duplicate WorkOrder per attempt. The JDK client never resends a POST; every
 *       retry is the dispatch retry queue's, one WorkOrder per attempt.</li>
 * </ul>
 * The global {@code RestClientCustomizer} in {@link DispatchRestClientConfig} does not apply, since
 * this client is not built from the shared {@code RestClient.Builder}.
 */
@Configuration
public class AgentDispatchRestClientConfig {

    /** The Agent contract: connect within 5s, answer the WorkOrder with a 2xx within 15s. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public RestClient agentDispatchRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(factory).build();
    }
}
