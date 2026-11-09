package com.hireai.application.port.webhook;

/**
 * SSRF guard for outbound webhook URLs: the URL must use HTTPS and every address its host
 * resolves to must be public (no loopback, private, link-local/metadata, CGNAT, multicast or
 * IPv6 unique-local). Throws a {@code VALIDATION_ERROR} {@code DomainException} on failure.
 * DNS rebinding between this check and the connection is a known gap.
 */
public interface WebhookUrlValidatorPort {

    /** A client webhook callback URL; the dev opt-in is {@code hireai.webhooks.allow-insecure-localhost}. */
    void assertDeliverable(String url);

    /**
     * An Agent's webhook URL, checked at registration, version publish and before every
     * WorkOrder send. Same rules and errors as {@link #assertDeliverable}; the dev opt-in for
     * {@code http://localhost} is {@code hireai.dispatch.allow-insecure-localhost}.
     */
    void assertAgentWebhookDeliverable(String url);
}
