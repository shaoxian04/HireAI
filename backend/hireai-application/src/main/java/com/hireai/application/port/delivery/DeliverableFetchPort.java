package com.hireai.application.port.delivery;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;

/**
 * Fetches an Agent's deliverable link once, so it can be frozen as a DeliverySnapshot (D24).
 * Implementations MUST apply Hard Invariant #6 as for every Agent-facing request: HTTPS only (bar
 * the dev localhost opt-in), the SSRF guard on the URL right before connecting, redirects never
 * followed, bounded connect and total time. They never throw for a bad link: every failure is a
 * {@link com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome.Unavailable} with a reason
 * the Client can read, and a body longer than {@code maxBytes} is
 * {@link com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome.TooLarge} (reading stops there).
 */
public interface DeliverableFetchPort {

    DeliveryFetch fetch(String url, long maxBytes);
}
