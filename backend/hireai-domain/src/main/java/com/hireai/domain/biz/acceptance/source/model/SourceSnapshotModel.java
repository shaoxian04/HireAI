package com.hireai.domain.biz.acceptance.source.model;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveredMediaType;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The frozen copy of a document a buyer uploaded under an Engagement (CONTEXT.md "SourceSnapshot",
 * spec S4: D30-D32). Upload only, into private storage; content-hashed and immutable. An Item that
 * references it is judged against exactly these bytes, which the Agent fetches through a signed link.
 *
 * <p>The record holds the metadata; the bytes live in the private content store under
 * {@link #sha256()}. Retention (D32): the clock starts ({@code retentionStartedAt}) once no Task using
 * the snapshot has a window left open, and restarts if one is booked again. When the Engagement's
 * retention period has passed, the content is deleted ({@code contentDeletedAt}) and the hash stays, so
 * a receipt naming it can still be checked against any copy someone holds.
 *
 * @param mediaType         what the platform judged the bytes to be ({@link DeliveredMediaType})
 * @param declaredMediaType the uploader's Content-Type essence, or null; kept only as a record of the claim
 */
public record SourceSnapshotModel(UUID id, UUID engagementId, UUID clientId, String filename, String mediaType,
                                  String declaredMediaType, long sizeBytes, String sha256, Instant uploadedAt,
                                  Instant retentionStartedAt, Instant contentDeletedAt) {

    public static final int MAX_FILENAME_LENGTH = 200;

    /** Freezes an uploaded document. Refuses an empty file, or one larger than {@code maxBytes}. */
    public static SourceSnapshotModel upload(UUID engagementId, UUID clientId, String filename,
                                             String declaredContentType, byte[] content, long maxBytes,
                                             Instant now) {
        if (content == null || content.length == 0) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "file: the document is empty");
        }
        if (content.length > maxBytes) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "file: the document is "
                    + content.length + " bytes; the limit is " + maxBytes);
        }
        return new SourceSnapshotModel(UUID.randomUUID(), engagementId, clientId, cleanFilename(filename),
                DeliveredMediaType.determine(declaredContentType, content),
                DeliveredMediaType.essence(declaredContentType),
                content.length, DeliverySnapshot.sha256Hex(content), now, null, null);
    }

    /**
     * The file name as shown and sent to the Agent: the last path segment, control characters removed,
     * at most {@value #MAX_FILENAME_LENGTH} characters; "document" when nothing is left.
     */
    static String cleanFilename(String raw) {
        String name = raw == null ? "" : raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.length() > MAX_FILENAME_LENGTH) {
            name = name.substring(0, MAX_FILENAME_LENGTH);
        }
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "document" : name;
    }

    public boolean isRetired() {
        return contentDeletedAt != null;
    }

    /** Refuses (409) attaching a snapshot whose content retention has already deleted. */
    public void requireContent() {
        if (isRetired()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "The document " + filename
                    + " was deleted at the end of its retention period; only its hash is kept. Upload it again.");
        }
    }

    /**
     * One retention observation (D32). While a Task using the snapshot still has a window open, the clock
     * is stopped; once none has, it starts (or keeps running), and once {@code retention} has passed since
     * it started, the content is retired. A retired snapshot never changes again.
     */
    public SourceSnapshotModel observeRetention(boolean anyWindowOpen, Instant now, Duration retention) {
        if (isRetired()) {
            return this;
        }
        if (anyWindowOpen) {
            return retentionStartedAt == null ? this : withRetention(null, null);
        }
        Instant started = retentionStartedAt == null ? now : retentionStartedAt;
        boolean due = !now.isBefore(started.plus(retention));
        return withRetention(started, due ? now : null);
    }

    private SourceSnapshotModel withRetention(Instant started, Instant deletedAt) {
        return new SourceSnapshotModel(id, engagementId, clientId, filename, mediaType, declaredMediaType,
                sizeBytes, sha256, uploadedAt, started, deletedAt);
    }
}
