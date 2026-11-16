package com.hireai.infrastructure.repository.acceptance.source;

import com.hireai.domain.biz.acceptance.source.enums.SourceDownloadOutcome;
import com.hireai.domain.biz.acceptance.source.model.SourceDownloadModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.domain.biz.acceptance.source.repository.SourceSnapshotRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code source_snapshots} and {@code source_downloads} (V49) with plain JDBC. The tables' triggers
 * freeze an uploaded snapshot (only its retention fields move) and keep the download log append-only.
 */
@Repository
public class SourceSnapshotRepositoryImpl implements SourceSnapshotRepository {

    private static final String COLUMNS = "id, engagement_id, client_id, filename, media_type, declared_media_type, "
            + "size_bytes, sha256, uploaded_at, retention_started_at, content_deleted_at";

    private static final RowMapper<SourceSnapshotModel> ROW = (rs, n) -> new SourceSnapshotModel(
            rs.getObject("id", UUID.class), rs.getObject("engagement_id", UUID.class),
            rs.getObject("client_id", UUID.class), rs.getString("filename"), rs.getString("media_type"),
            rs.getString("declared_media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
            instant(rs.getTimestamp("uploaded_at")), instant(rs.getTimestamp("retention_started_at")),
            instant(rs.getTimestamp("content_deleted_at")));

    private static final RowMapper<SourceDownloadModel> DOWNLOAD = (rs, n) -> new SourceDownloadModel(
            rs.getObject("id", UUID.class), rs.getObject("source_snapshot_id", UUID.class),
            rs.getObject("task_id", UUID.class), rs.getObject("item_id", UUID.class), rs.getInt("item_no"),
            SourceDownloadOutcome.valueOf(rs.getString("outcome")), rs.getString("remote_address"),
            rs.getString("user_agent"), instant(rs.getTimestamp("created_at")));

    private final NamedParameterJdbcTemplate jdbc;

    public SourceSnapshotRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SourceSnapshotModel s) {
        jdbc.update("""
                INSERT INTO source_snapshots (id, engagement_id, client_id, filename, media_type, declared_media_type,
                                              size_bytes, sha256, uploaded_at, retention_started_at, content_deleted_at)
                VALUES (:id, :engagementId, :clientId, :filename, :mediaType, :declaredMediaType,
                        :sizeBytes, :sha256, :uploadedAt, :retentionStartedAt, :contentDeletedAt)
                """, params(s));
    }

    @Override
    public void updateRetention(SourceSnapshotModel s) {
        jdbc.update("UPDATE source_snapshots SET retention_started_at = :retentionStartedAt, "
                + "content_deleted_at = :contentDeletedAt WHERE id = :id", params(s));
    }

    @Override
    public Optional<SourceSnapshotModel> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_snapshots WHERE id = :id", Map.of("id", id), ROW)
                .stream().findFirst();
    }

    @Override
    public Map<UUID, SourceSnapshotModel> lockByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM source_snapshots WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                        Map.of("ids", ids), ROW).stream()
                .collect(Collectors.toMap(SourceSnapshotModel::id, Function.identity()));
    }

    @Override
    public Optional<SourceSnapshotModel> lockById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_snapshots WHERE id = :id FOR UPDATE",
                Map.of("id", id), ROW).stream().findFirst();
    }

    @Override
    public List<SourceSnapshotModel> findByEngagementId(UUID engagementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM source_snapshots WHERE engagement_id = :id "
                + "ORDER BY uploaded_at DESC, id", Map.of("id", engagementId), ROW);
    }

    @Override
    public List<UUID> idsHoldingContent() {
        return jdbc.queryForList("SELECT id FROM source_snapshots WHERE content_deleted_at IS NULL ORDER BY uploaded_at",
                Map.of(), UUID.class);
    }

    @Override
    public void recordDownload(SourceDownloadModel d) {
        jdbc.update("""
                INSERT INTO source_downloads (id, source_snapshot_id, task_id, item_id, item_no, outcome,
                                              remote_address, user_agent, created_at)
                VALUES (:id, :snapshotId, :taskId, :itemId, :itemNo, :outcome, :remoteAddress, :userAgent, :at)
                """, new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("snapshotId", d.sourceSnapshotId())
                .addValue("taskId", d.taskId())
                .addValue("itemId", d.itemId())
                .addValue("itemNo", d.itemNo())
                .addValue("outcome", d.outcome().name())
                .addValue("remoteAddress", d.remoteAddress())
                .addValue("userAgent", d.userAgent())
                .addValue("at", Timestamp.from(d.at())));
    }

    @Override
    public Map<UUID, List<SourceDownloadModel>> downloadsOf(Collection<UUID> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT * FROM source_downloads WHERE source_snapshot_id IN (:ids) "
                        + "ORDER BY created_at, id", Map.of("ids", snapshotIds), DOWNLOAD).stream()
                .collect(Collectors.groupingBy(SourceDownloadModel::sourceSnapshotId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static MapSqlParameterSource params(SourceSnapshotModel s) {
        return new MapSqlParameterSource()
                .addValue("id", s.id())
                .addValue("engagementId", s.engagementId())
                .addValue("clientId", s.clientId())
                .addValue("filename", s.filename())
                .addValue("mediaType", s.mediaType())
                .addValue("declaredMediaType", s.declaredMediaType())
                .addValue("sizeBytes", s.sizeBytes())
                .addValue("sha256", s.sha256())
                .addValue("uploadedAt", ts(s.uploadedAt()))
                .addValue("retentionStartedAt", ts(s.retentionStartedAt()))
                .addValue("contentDeletedAt", ts(s.contentDeletedAt()));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
