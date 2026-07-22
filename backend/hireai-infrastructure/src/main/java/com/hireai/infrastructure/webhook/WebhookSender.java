package com.hireai.infrastructure.webhook;

import com.hireai.application.port.webhook.WebhookSendResult;
import com.hireai.application.port.webhook.WebhookSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;

@Component
@Slf4j
public class WebhookSender implements WebhookSenderPort {

    private final RestClient restClient;

    /**
     * Injects the dedicated {@code webhookRestClient} (redirects disabled + webhook timeouts,
     * see {@link WebhookRestClientConfig}) — NOT the shared auto-configured builder, so the
     * Inv #6 SSRF guard cannot be bypassed via a callback's 3xx redirect to an internal host.
     */
    public WebhookSender(RestClient webhookRestClient) {
        this.restClient = webhookRestClient;
    }

    @Override
    public WebhookSendResult send(String url, String body, String signatureHeader, String eventId, String eventType) {
        try {
            var response = restClient.post()
                    .uri(URI.create(url))
                    .header("X-HireAI-Signature", signatureHeader)
                    .header("X-HireAI-Event-Id", eventId)
                    .header("X-HireAI-Event-Type", eventType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            return WebhookSendResult.ok(status);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return WebhookSendResult.fail(e.getStatusCode().value(), e.getMessage());
        } catch (Exception e) {
            log.warn("Webhook POST to {} failed: {}", url, e.toString());
            return WebhookSendResult.fail(0, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
