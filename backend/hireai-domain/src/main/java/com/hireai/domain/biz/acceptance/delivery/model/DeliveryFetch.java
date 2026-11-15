package com.hireai.domain.biz.acceptance.delivery.model;

/**
 * What fetching an Agent's deliverable link produced (the fetch port's answer). Only
 * {@link Fetched} holds bytes; {@link DeliveryOutcome.TooLarge} and {@link DeliveryOutcome.Unavailable}
 * are also {@link DeliveryOutcome}s, since nothing more is learnt from them.
 */
public sealed interface DeliveryFetch permits DeliveryFetch.Fetched, DeliveryOutcome.TooLarge, DeliveryOutcome.Unavailable {

    /** The whole body, within the byte limit asked for, and the response's Content-Type (nullable). */
    record Fetched(byte[] content, String contentType) implements DeliveryFetch {
        public Fetched {
            if (content == null) {
                throw new IllegalArgumentException("Fetched content is required");
            }
        }
    }
}
