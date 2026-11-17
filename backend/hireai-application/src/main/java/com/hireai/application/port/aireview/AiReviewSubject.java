package com.hireai.application.port.aireview;

import java.util.List;

/**
 * What the reviewer judges: the Client's brief and criteria (trusted, the Client's own words) and the
 * deliverable (untrusted, the Agent's). The deliverable is the result's text, or the bytes of its
 * frozen DeliverySnapshot with the media type the platform judged them to be.
 *
 * @param text       the result payload, or null for a file deliverable
 * @param content    the DeliverySnapshot's bytes, or null for a text deliverable
 * @param mediaType  the snapshot's media type (null for text)
 * @param sha256     the snapshot's content hash (null for text)
 */
public record AiReviewSubject(String title, String description, List<String> criteria, String text,
                              byte[] content, String mediaType, String sha256) {

    public AiReviewSubject {
        criteria = List.copyOf(criteria);
    }

    public boolean isFile() {
        return content != null;
    }
}
