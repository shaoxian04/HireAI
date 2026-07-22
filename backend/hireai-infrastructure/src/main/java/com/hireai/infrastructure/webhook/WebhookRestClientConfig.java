package com.hireai.infrastructure.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Dedicated {@link RestClient} for outbound webhook delivery.
 *
 * <p>Unlike the shared {@code DispatchRestClientConfig} customizer, this client <b>disables HTTP
 * redirect following</b> ({@link HttpClient.Redirect#NEVER}). This is load-bearing for Hard
 * Invariant #6 (SSRF): {@code WebhookUrlValidator} only validates the registered callback URL, so a
 * validation-passing public URL that responds with a 3xx to an internal host must never be followed —
 * otherwise the guard could be silently bypassed. It also applies the webhook-specific
 * {@code hireai.webhooks.connect-timeout}/{@code read-timeout} bounds (rather than inheriting the
 * global 5s/15s), which caps how long the delivery sweeper holds a claimed row across the POST.
 */
@Configuration
public class WebhookRestClientConfig {

    @Bean
    public RestClient webhookRestClient(
            @Value("${hireai.webhooks.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${hireai.webhooks.read-timeout:PT5S}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }
}
