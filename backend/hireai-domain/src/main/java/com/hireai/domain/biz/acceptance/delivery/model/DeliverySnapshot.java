package com.hireai.domain.biz.acceptance.delivery.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The frozen copy of what an Agent delivered for a result (D24, CONTEXT.md "DeliverySnapshot").
 * Captured once, when the result arrives, through the SSRF-guarded fetch; immutable after that.
 * Every later step (the file_basic checks, the Client's review, arbitration) judges this copy,
 * never {@link #sourceUrl()}, so a swapped or deleted link changes nothing.
 *
 * <p>The record holds the metadata; the bytes live in content-addressed storage under
 * {@link #sha256()} (the application's {@code DeliveryContentStore} port). One snapshot per
 * {@code taskResultId}: a revised result is a new result and gets its own.
 *
 * @param mediaType         what the platform judged the bytes to be ({@link DeliveredMediaType})
 * @param declaredMediaType the Agent's Content-Type essence, or null; kept only as a record of the claim
 */
public record DeliverySnapshot(UUID id, UUID taskId, UUID taskResultId, String sourceUrl, String mediaType,
                               String declaredMediaType, long sizeBytes, String sha256, Instant fetchedAt) {

    public DeliverySnapshot {
        if (id == null || taskId == null || taskResultId == null || sourceUrl == null || mediaType == null
                || sha256 == null || fetchedAt == null || sizeBytes < 0) {
            throw new IllegalArgumentException("A DeliverySnapshot needs every field but the declared type");
        }
    }

    public static DeliverySnapshot capture(UUID taskId, UUID taskResultId, String sourceUrl,
                                           DeliveryFetch.Fetched fetched, Instant fetchedAt) {
        byte[] content = fetched.content();
        return new DeliverySnapshot(UUID.randomUUID(), taskId, taskResultId, sourceUrl,
                DeliveredMediaType.determine(fetched.contentType(), content),
                DeliveredMediaType.essence(fetched.contentType()),
                content.length, sha256Hex(content), fetchedAt);
    }

    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
