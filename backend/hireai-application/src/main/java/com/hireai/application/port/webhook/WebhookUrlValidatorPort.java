package com.hireai.application.port.webhook;

/** Validates that a client callback URL is safe to POST to (HTTPS + non-private). Throws on failure. */
public interface WebhookUrlValidatorPort {
    void assertDeliverable(String url);
}
