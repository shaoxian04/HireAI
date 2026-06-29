package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Dispatches a {@link DispatchMessage} to an Agent's webhook over a signed HTTPS POST
 * (CONTRACTS wire contract B). Enforces Hard Invariant #6: a non-HTTPS webhook URL is
 * rejected by throwing, except an {@code http://localhost} URL when the dev-profile flag
 * {@code hireai.dispatch.allow-insecure-localhost} is true (the signed-token check still
 * applies in every profile). Bounded connect/read timeouts are applied to the injected
 * {@code RestClient.Builder} bean by {@link DispatchRestClientConfig} (a
 * {@code RestClientCustomizer}); the client builds straight from the supplied builder so
 * any pre-installed request factory — e.g. a test's {@code MockRestServiceServer} — is preserved.
 */
@Component
@Slf4j
public class AgentDispatchClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean allowInsecureLocalhost;

    public AgentDispatchClient(RestClient.Builder restClientBuilder,
                               ObjectMapper objectMapper,
                               @Value("${hireai.dispatch.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    public void dispatch(DispatchMessage message, String token) {
        String webhookUrl = message.webhookUrl();
        requireSecureWebhook(webhookUrl);
        WebhookDispatchBody body = buildBody(message);

        restClient.post()
                .uri(URI.create(webhookUrl))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Correlation-ID", message.correlationId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Dispatched task {} to webhook (correlationId={})", message.taskId(), message.correlationId());
    }

    private WebhookDispatchBody buildBody(DispatchMessage message) {
        TaskDispatchPayload payload = message.payload();
        return new WebhookDispatchBody(
                message.taskId(),
                payload.category(),
                payload.title(),
                payload.description(),
                readJson(payload.expectedDeliverableJson()),
                readJson(payload.outputSpecJson()),
                payload.callbackUrl());
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Dispatch payload contains malformed JSON", ex);
        }
    }

    private void requireSecureWebhook(String webhookUrl) {
        if (webhookUrl == null) {
            throw new IllegalArgumentException("Webhook URL must not be null");
        }
        if (webhookUrl.startsWith("https://")) {
            return;
        }
        if (allowInsecureLocalhost && isHttpLocalhost(webhookUrl)) {
            log.warn("Dispatching to insecure localhost webhook {} (dev profile)", webhookUrl);
            return;
        }
        throw new IllegalArgumentException("Webhook URL must use HTTPS: " + webhookUrl);
    }

    private boolean isHttpLocalhost(String webhookUrl) {
        try {
            URI uri = URI.create(webhookUrl);
            String host = uri.getHost();
            return "http".equals(uri.getScheme())
                    && ("localhost".equals(host) || "127.0.0.1".equals(host));
        } catch (Exception ex) {
            return false;
        }
    }
}
