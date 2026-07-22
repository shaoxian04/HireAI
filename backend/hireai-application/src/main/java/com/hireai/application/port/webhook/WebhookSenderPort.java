package com.hireai.application.port.webhook;

public interface WebhookSenderPort {
    WebhookSendResult send(String url, String body, String signatureHeader, String eventId, String eventType);
}
