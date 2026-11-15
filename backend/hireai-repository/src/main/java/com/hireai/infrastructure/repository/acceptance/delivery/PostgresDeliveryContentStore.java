package com.hireai.infrastructure.repository.acceptance.delivery;

import com.hireai.application.port.delivery.DeliveryContentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link DeliveryContentStore} on {@code delivery_snapshot_contents} (V44): bytes in a BYTEA,
 * keyed by their SHA-256, so identical deliverables are stored once. Bounded by
 * {@code hireai.delivery.max-bytes}; replace this adapter (not the callers) to move the bytes to a
 * private object-storage bucket.
 */
@Repository
public class PostgresDeliveryContentStore implements DeliveryContentStore {

    private final JdbcTemplate jdbc;

    public PostgresDeliveryContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(String sha256, byte[] content) {
        jdbc.update("INSERT INTO delivery_snapshot_contents (sha256, content, size_bytes) VALUES (?, ?, ?) "
                + "ON CONFLICT (sha256) DO NOTHING", sha256, content, (long) content.length);
    }

    @Override
    public Optional<byte[]> get(String sha256) {
        return jdbc.query("SELECT content FROM delivery_snapshot_contents WHERE sha256 = ?",
                (rs, n) -> rs.getBytes("content"), sha256).stream().findFirst();
    }
}
