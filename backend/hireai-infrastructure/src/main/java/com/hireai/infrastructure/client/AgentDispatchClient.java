package com.hireai.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.port.webhook.WebhookUrlValidatorPort;
import com.hireai.domain.biz.task.routing.info.DispatchMessage;
import com.hireai.domain.biz.task.routing.info.TaskDispatchPayload;
import lombok.extern.slf4j.Slf4j;
import com.hireai.utility.exception.DomainException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;

/**
 * Dispatches a {@link DispatchMessage} to an Agent's webhook over a signed HTTPS POST
 * (CONTRACTS wire contract B). Enforces Hard Invariant #6:
 * <ul>
 *   <li>A non-HTTPS webhook URL is rejected by throwing (a platform-side error), except an
 *       {@code http://localhost} URL when {@code hireai.dispatch.allow-insecure-localhost} is true
 *       (the signed-token check still applies in every profile).</li>
 *   <li>Before every send the URL goes through the SSRF guard again
 *       ({@link WebhookUrlValidatorPort#assertAgentWebhookDeliverable}), so a host whose DNS answer
 *       changed to an internal address since registration is never contacted. That rejection is
 *       a failed send ({@link AgentSendFailedException}, no status): the Builder's endpoint is unusable.</li>
 *   <li>Redirects are never followed, and the client never resends a POST by itself: see
 *       {@link AgentDispatchRestClientConfig}. A 3xx reply is a failed send carrying its status.</li>
 * </ul>
 * A failure of the HTTP send itself (non-2xx reply, unreachable endpoint, timeout) is thrown as
 * {@link AgentSendFailedException}; anything else (e.g. a non-HTTPS URL) is a platform-side error.
 */
@Component
@Slf4j
public class AgentDispatchClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final WebhookUrlValidatorPort urlValidator;
    private final boolean allowInsecureLocalhost;

    public AgentDispatchClient(@Qualifier("agentDispatchRestClient") RestClient restClient,
                               ObjectMapper objectMapper,
                               WebhookUrlValidatorPort urlValidator,
                               @Value("${hireai.dispatch.allow-insecure-localhost:false}") boolean allowInsecureLocalhost) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.urlValidator = urlValidator;
        this.allowInsecureLocalhost = allowInsecureLocalhost;
    }

    public void dispatch(DispatchMessage message, String token) {
        String webhookUrl = message.webhookUrl();
        requireSecureWebhook(webhookUrl);
        WebhookDispatchBody body = buildBody(message);
        try {
            urlValidator.assertAgentWebhookDeliverable(webhookUrl);
        } catch (DomainException ex) {
            throw new AgentSendFailedException("Agent webhook for task " + message.taskId()
                    + " failed the SSRF guard at send time: " + ex.getMessage(), null, ex);
        }

        try {
            restClient.post()
                    .uri(URI.create(webhookUrl))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-Correlation-ID", message.correlationId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        int status = response.getStatusCode().value();
                        throw new AgentSendFailedException("Agent webhook for task " + message.taskId()
                                + " replied " + status, status, null);
                    })
                    .toBodilessEntity();
        } catch (ResourceAccessException ex) {
            throw new AgentSendFailedException("Agent webhook for task " + message.taskId()
                    + " was unreachable or timed out", null, ex);
        }

        log.info("Dispatched task {} to webhook (correlationId={})", message.taskId(), message.correlationId());
    }

    private WebhookDispatchBody buildBody(DispatchMessage message) {
        TaskDispatchPayload payload = message.payload();
        return new WebhookDispatchBody(
                message.taskId(),
                message.agentVersionId(),
                payload.title(),
                payload.description(),
                payload.category(),
                readJson(payload.outputSpecJson()),
                payload.executionDeadline() == null ? null : payload.executionDeadline().toString(),
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
