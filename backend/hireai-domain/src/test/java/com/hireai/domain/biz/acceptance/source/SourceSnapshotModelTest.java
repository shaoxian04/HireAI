package com.hireai.domain.biz.acceptance.source;

import com.hireai.domain.biz.acceptance.source.model.SourceSnapshotModel;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A SourceSnapshot (spec S4: D30, D32): frozen at upload, retired after its retention period. */
class SourceSnapshotModelTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final byte[] PDF = "%PDF-1.7\nhello".getBytes(StandardCharsets.US_ASCII);

    @Test
    void anUploadIsHashedAndTypedByItsBytesNotItsClaim() {
        SourceSnapshotModel s = upload("C:\\scans\\invoice 7.pdf", "text/plain", PDF);

        // sha256("%PDF-1.7\nhello"), computed independently.
        assertThat(s.sha256()).isEqualTo("79d6a59e1f914ed60b8216fcec3f3732c959e1636527e68c415bb52c1ef4ee79");
        assertThat(s.mediaType()).isEqualTo("application/pdf");
        assertThat(s.declaredMediaType()).isEqualTo("text/plain");
        assertThat(s.sizeBytes()).isEqualTo(PDF.length);
        assertThat(s.filename()).isEqualTo("invoice 7.pdf");
        assertThat(s.isRetired()).isFalse();
        assertThat(upload("../..", null, PDF).filename()).isEqualTo("document");
        assertThat(upload("a\u0000b\nc.txt", null, PDF).filename()).isEqualTo("abc.txt");
    }

    @Test
    void anEmptyOrOversizeUploadIsRefused() {
        assertThatThrownBy(() -> upload("x", null, new byte[0])).isInstanceOf(DomainException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> SourceSnapshotModel.upload(UUID.randomUUID(), UUID.randomUUID(), "x", null,
                new byte[11], 10, NOW)).isInstanceOf(DomainException.class).hasMessageContaining("limit is 10");
    }

    @Test
    void retentionWaitsForEveryWindowToCloseThenRunsItsPeriodThenKeepsOnlyTheHash() {
        SourceSnapshotModel s = upload("a.pdf", null, PDF);

        SourceSnapshotModel open = s.observeRetention(true, NOW, THIRTY_DAYS);
        assertThat(open.retentionStartedAt()).isNull();

        SourceSnapshotModel closed = open.observeRetention(false, NOW.plus(Duration.ofDays(2)), THIRTY_DAYS);
        assertThat(closed.retentionStartedAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThat(closed.isRetired()).isFalse();

        SourceSnapshotModel stillKept = closed.observeRetention(false, NOW.plus(Duration.ofDays(31)), THIRTY_DAYS);
        assertThat(stillKept.isRetired()).isFalse();
        assertThat(stillKept.retentionStartedAt()).isEqualTo(NOW.plus(Duration.ofDays(2)));

        Instant due = NOW.plus(Duration.ofDays(32));
        SourceSnapshotModel retired = stillKept.observeRetention(false, due, THIRTY_DAYS);
        assertThat(retired.contentDeletedAt()).isEqualTo(due);
        assertThat(retired.sha256()).isEqualTo(s.sha256());
        assertThat(retired.observeRetention(true, due.plusSeconds(1), THIRTY_DAYS)).isEqualTo(retired);
        assertThatThrownBy(retired::requireContent).isInstanceOf(DomainException.class)
                .hasMessageContaining("only its hash is kept");
    }

    @Test
    void bookingItAgainStopsTheRetentionClock() {
        SourceSnapshotModel running = upload("a.pdf", null, PDF).observeRetention(false, NOW, THIRTY_DAYS);

        SourceSnapshotModel reused = running.observeRetention(true, NOW.plus(Duration.ofDays(29)), THIRTY_DAYS);

        assertThat(reused.retentionStartedAt()).isNull();
        assertThat(reused.observeRetention(false, NOW.plus(Duration.ofDays(40)), THIRTY_DAYS).isRetired()).isFalse();
    }

    private static SourceSnapshotModel upload(String name, String type, byte[] bytes) {
        return SourceSnapshotModel.upload(UUID.randomUUID(), UUID.randomUUID(), name, type, bytes, 1_000_000, NOW);
    }
}
