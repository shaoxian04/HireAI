package com.hireai.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
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

    /** The Agent contract: connect within the connect timeout, answer the WorkOrder with a 2xx within the read timeout. */
    @Bean
    public AgentDispatchTimeouts agentDispatchTimeouts(
            @Value("${hireai.dispatch.connect-timeout}") Duration connect,
            @Value("${hireai.dispatch.read-timeout}") Duration read) {
        return new AgentDispatchTimeouts(connect, read);
    }

    @Bean
    public RestClient agentDispatchRestClient(AgentDispatchTimeouts timeouts) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeouts.connect())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeouts.read());
        return RestClient.builder().requestFactory(factory).build();
    }
}
