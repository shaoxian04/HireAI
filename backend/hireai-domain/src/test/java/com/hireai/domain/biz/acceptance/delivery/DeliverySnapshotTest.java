package com.hireai.domain.biz.acceptance.delivery;

import com.hireai.domain.biz.acceptance.delivery.model.DeliveryFetch;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliverySnapshotTest {

    @Test
    void captureHashesTheBytesAndRecordsWhatTheyAre() {
        UUID task = UUID.randomUUID();
        UUID result = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-15T10:00:00Z");
        DeliverySnapshot snapshot = DeliverySnapshot.capture(task, result, "https://cdn.example.com/a.txt",
                new DeliveryFetch.Fetched("abc".getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"), at);

        // Known SHA-256 of "abc" (FIPS 180-2 test vector).
        assertThat(snapshot.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(snapshot.sizeBytes()).isEqualTo(3);
        assertThat(snapshot.mediaType()).isEqualTo("text/plain");
        assertThat(snapshot.declaredMediaType()).isEqualTo("text/plain");
        assertThat(snapshot.taskId()).isEqualTo(task);
        assertThat(snapshot.taskResultId()).isEqualTo(result);
        assertThat(snapshot.sourceUrl()).isEqualTo("https://cdn.example.com/a.txt");
        assertThat(snapshot.fetchedAt()).isEqualTo(at);
    }
}
