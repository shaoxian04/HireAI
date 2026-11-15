package com.hireai.application.port.delivery;

import java.util.Optional;

/**
 * Private, content-addressed storage for DeliverySnapshot bytes (D24), keyed by the lowercase hex
 * SHA-256 of the content. Never public: the bytes are served only through owner-checked endpoints.
 *
 * <p><b>Storage seam.</b> Today's adapter keeps the bytes in Postgres ({@code delivery_snapshot_contents}),
 * which bounds snapshots to {@code hireai.delivery.max-bytes}. A move to object storage (a private
 * bucket) replaces only this adapter; snapshots reference content by hash, not by location. The
 * same port is meant to hold SourceSnapshot bytes (S4) and to be where D32 retention deletes
 * content while the hashes stay. It is NOT the public {@code MediaStoragePort} (D30).
 */
public interface DeliveryContentStore {

    /** Stores {@code content} under {@code sha256}. Idempotent: storing the same hash again is a no-op. */
    void put(String sha256, byte[] content);

    /** The bytes stored under {@code sha256}, or empty when they were never stored or have been retired. */
    Optional<byte[]> get(String sha256);
}
