package com.hireai.application.port.delivery;

import java.util.Optional;

/**
 * Private, content-addressed storage for snapshot bytes, keyed by the lowercase hex SHA-256 of the
 * content: DeliverySnapshots (D24) and SourceSnapshots (S4, D30). Never public: the bytes are served
 * only through owner-checked endpoints or an Item's signed source link. It is NOT the public
 * {@code MediaStoragePort} (D30).
 *
 * <p><b>Storage seam.</b> Today's adapter keeps the bytes in Postgres ({@code delivery_snapshot_contents}).
 * A move to object storage (a private bucket) replaces only this adapter; snapshots reference content
 * by hash, not by location.
 */
public interface DeliveryContentStore {

    /** Stores {@code content} under {@code sha256}. Idempotent: storing the same hash again is a no-op. */
    void put(String sha256, byte[] content);

    /** The bytes stored under {@code sha256}, or empty when they were never stored or have been retired. */
    Optional<byte[]> get(String sha256);

    /**
     * D32 retention: deletes the bytes under {@code sha256} unless a DeliverySnapshot or a SourceSnapshot
     * that still holds its content references them (the caller marks its own snapshot retired first, in
     * the same transaction). The hashes stay. Returns whether the bytes were deleted.
     */
    boolean retire(String sha256);
}
