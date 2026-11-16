package com.hireai.domain.biz.acceptance.source.repository;

import com.hireai.domain.biz.acceptance.source.model.SourceDownloadModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link SourceSnapshotModel} and its download log ({@link SourceDownloadModel}). */
public interface SourceSnapshotRepository {

    void insert(SourceSnapshotModel snapshot);

    /** Writes the retention fields, the only ones that change. */
    void updateRetention(SourceSnapshotModel snapshot);

    Optional<SourceSnapshotModel> findById(UUID id);

    /**
     * These snapshots, row-locked until the transaction ends, so retention cannot retire one while a
     * booking attaches it (and the retention pass, which takes the same lock, then sees the booking).
     */
    Map<UUID, SourceSnapshotModel> lockByIds(Collection<UUID> ids);

    /** Row-locks one snapshot (the retention pass). */
    Optional<SourceSnapshotModel> lockById(UUID id);

    /** The Engagement's snapshots, newest first. */
    List<SourceSnapshotModel> findByEngagementId(UUID engagementId);

    /** Ids of every snapshot whose content is still held (retention candidates). */
    List<UUID> idsHoldingContent();

    void recordDownload(SourceDownloadModel download);

    /** The download log of these snapshots, oldest first, grouped by snapshot. */
    Map<UUID, List<SourceDownloadModel>> downloadsOf(Collection<UUID> snapshotIds);
}
