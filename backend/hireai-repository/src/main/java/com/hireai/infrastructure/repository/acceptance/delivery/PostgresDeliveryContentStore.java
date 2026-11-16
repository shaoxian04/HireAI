package com.hireai.infrastructure.repository.acceptance.delivery;

import com.hireai.application.port.delivery.DeliveryContentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link DeliveryContentStore} on {@code delivery_snapshot_contents} (V44): bytes in a BYTEA,
 * keyed by their SHA-256, so identical files are stored once, whether an Agent delivered them or a
 * buyer uploaded them. Bounded by the upload and delivery byte limits; replace this adapter (not the
 * callers) to move the bytes to a private object-storage bucket.
 *
 * <p>{@link #put} and {@link #retire} take the same per-hash transaction advisory lock, so retiring a
 * hash waits for a transaction storing (and referencing) the same bytes, then sees its reference.
 */
@Repository
public class PostgresDeliveryContentStore implements DeliveryContentStore {

    private static final String LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?, 104))";

    private final JdbcTemplate jdbc;

    public PostgresDeliveryContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(String sha256, byte[] content) {
        jdbc.query(LOCK, rs -> { }, sha256);
        jdbc.update("INSERT INTO delivery_snapshot_contents (sha256, content, size_bytes) VALUES (?, ?, ?) "
                + "ON CONFLICT (sha256) DO NOTHING", sha256, content, (long) content.length);
    }

    @Override
    public Optional<byte[]> get(String sha256) {
        return jdbc.query("SELECT content FROM delivery_snapshot_contents WHERE sha256 = ?",
                (rs, n) -> rs.getBytes("content"), sha256).stream().findFirst();
    }

    @Override
    public boolean retire(String sha256) {
        jdbc.query(LOCK, rs -> { }, sha256);
        return jdbc.update("""
                DELETE FROM delivery_snapshot_contents c WHERE c.sha256 = ?
                  AND NOT EXISTS (SELECT 1 FROM delivery_snapshots d WHERE d.sha256 = c.sha256)
                  AND NOT EXISTS (SELECT 1 FROM source_snapshots s
                                  WHERE s.sha256 = c.sha256 AND s.content_deleted_at IS NULL)
                """, sha256) > 0;
    }
}
