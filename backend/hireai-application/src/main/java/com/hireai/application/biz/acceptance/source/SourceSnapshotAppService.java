package com.hireai.application.biz.acceptance.source;

import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.source.model.SourceDownloadModel;
import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.domain.biz.task.item.info.DispatchItem;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SourceSnapshots (spec S4: D30-D32): documents a buyer uploads under an Engagement, into private
 * storage, frozen and content-hashed; the signed per-Item links the WorkOrder carries to them; the log of
 * every download; and D32 retention.
 */
public interface SourceSnapshotAppService {

    /** Freezes an uploaded document under the buyer's own ACTIVE Engagement (upload only: no URL fetch). */
    SourceSnapshotModel upload(UUID engagementId, UUID clientId, String filename, String contentType, byte[] content);

    /** The buyer's view of an Engagement's documents, each with its download log, newest first. */
    List<SourceView> listForEngagement(UUID engagementId, UUID clientId);

    /**
     * Inside a booking's transaction: row-locks the documents the Items reference and checks each was
     * uploaded under {@code engagement} and still holds its content. Returns them by id.
     */
    Map<UUID, SourceSnapshotModel> attachForBooking(EngagementModel engagement, Collection<UUID> sourceIds);

    /**
     * The WorkOrder's Items with a signed link on each Item whose input is a SourceSnapshot, expiring at
     * {@code executionDeadline} (D31). Items without a source pass through unchanged.
     */
    List<DispatchItem> withSignedLinks(List<DispatchItem> items, Instant executionDeadline);

    /** Serves (or refuses) a download through a signed link, and logs it (D31). */
    Download download(String token, String remoteAddress, String userAgent);

    /** Ids of every snapshot whose content is still held: the retention pass's candidates. */
    List<UUID> retentionCandidateIds();

    /** One D32 retention observation of one snapshot, in its own transaction. Returns whether it retired. */
    boolean applyRetention(UUID sourceSnapshotId);

    record SourceView(SourceSnapshotModel snapshot, List<SourceDownloadModel> downloads) {
    }

    /** What a signed-link download got. */
    sealed interface Download {
        record Served(SourceSnapshotModel snapshot, byte[] content) implements Download {
        }

        /** {@code reason}: why it was refused. */
        record Refused(Reason reason) implements Download {
        }

        enum Reason {
            /** Malformed, forged, or naming no source Item: nothing to attribute it to. */
            INVALID,
            /** Past the execution deadline (logged). */
            EXPIRED,
            /** Retention deleted the content (logged). */
            RETIRED
        }
    }
}
