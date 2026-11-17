package com.hireai.domain.biz.acceptance.delivery.model;

/**
 * What the file_basic checks judge (D24): the frozen {@link DeliverySnapshot}, or why there is none.
 * A missing snapshot always fails the delivery; nothing is ever judged from the Agent's live link.
 */
public sealed interface DeliveryOutcome
        permits DeliveryOutcome.Captured, DeliveryOutcome.TooLarge, DeliveryOutcome.Unavailable {

    /** The deliverable was fetched and frozen. */
    record Captured(DeliverySnapshot snapshot) implements DeliveryOutcome {
        public Captured {
            if (snapshot == null) {
                throw new IllegalArgumentException("A captured delivery needs its snapshot");
            }
        }
    }

    /** The body ran past {@code limitBytes}; reading stopped there and nothing was kept. */
    record TooLarge(long limitBytes) implements DeliveryOutcome, DeliveryFetch {
    }

    /**
     * The link could not be fetched: refused by the SSRF guard, unreachable, timed out, or answered
     * with anything but a 2xx (a redirect is never followed). {@code reason} is shown to the Client.
     */
    record Unavailable(String reason) implements DeliveryOutcome, DeliveryFetch {
    }
}
