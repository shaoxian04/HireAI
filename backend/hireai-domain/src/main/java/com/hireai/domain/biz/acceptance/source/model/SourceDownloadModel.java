package com.hireai.domain.biz.acceptance.source.model;

import com.hireai.domain.biz.acceptance.source.enums.SourceDownloadOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to download a SourceSnapshot through an Item's signed link (D31): every attempt whose link
 * the platform signed is logged, served or not. Append-only.
 */
public record SourceDownloadModel(UUID id, UUID sourceSnapshotId, UUID taskId, UUID itemId, int itemNo,
                                  SourceDownloadOutcome outcome, String remoteAddress, String userAgent,
                                  Instant at) {

    public static SourceDownloadModel record(UUID sourceSnapshotId, UUID taskId, UUID itemId, int itemNo,
                                             SourceDownloadOutcome outcome, String remoteAddress,
                                             String userAgent, Instant at) {
        return new SourceDownloadModel(UUID.randomUUID(), sourceSnapshotId, taskId, itemId, itemNo, outcome,
                truncate(remoteAddress, 64), truncate(userAgent, 300), at);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
