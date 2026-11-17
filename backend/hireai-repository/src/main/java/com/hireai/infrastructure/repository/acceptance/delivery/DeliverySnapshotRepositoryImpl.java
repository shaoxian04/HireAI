package com.hireai.infrastructure.repository.acceptance.delivery;

import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.delivery.repository.DeliverySnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** {@code delivery_snapshots} (V44, {@code item_id} V57) with plain JDBC: insert-only, the table's trigger refuses changes. */
@Repository
public class DeliverySnapshotRepositoryImpl implements DeliverySnapshotRepository {

    private static final RowMapper<DeliverySnapshot> ROW = (rs, n) -> new DeliverySnapshot(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
            rs.getObject("task_result_id", UUID.class), rs.getString("source_url"), rs.getString("media_type"),
            rs.getString("declared_media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
            rs.getTimestamp("fetched_at").toInstant(), rs.getObject("item_id", UUID.class));

    private final JdbcTemplate jdbc;

    public DeliverySnapshotRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DeliverySnapshot save(DeliverySnapshot s) {
        jdbc.update("INSERT INTO delivery_snapshots (id, task_id, task_result_id, source_url, media_type, "
                        + "declared_media_type, size_bytes, sha256, fetched_at, item_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                s.id(), s.taskId(), s.taskResultId(), s.sourceUrl(), s.mediaType(), s.declaredMediaType(),
                s.sizeBytes(), s.sha256(), Timestamp.from(s.fetchedAt()), s.itemId());
        return s;
    }

    @Override
    public Optional<DeliverySnapshot> findByTaskResultId(UUID taskResultId) {
        return jdbc.query("SELECT * FROM delivery_snapshots WHERE task_result_id = ?", ROW, taskResultId)
                .stream().findFirst();
    }

    @Override
    public Optional<DeliverySnapshot> findLatestForItem(UUID itemId) {
        // An Item's first result is keyed by the Item's own id, so a row from before V57 (no item_id) is found too.
        return jdbc.query("SELECT * FROM delivery_snapshots WHERE item_id = ? OR task_result_id = ? "
                + "ORDER BY fetched_at DESC, id LIMIT 1", ROW, itemId, itemId).stream().findFirst();
    }
}
