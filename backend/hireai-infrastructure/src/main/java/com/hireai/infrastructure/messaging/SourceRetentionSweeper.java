package com.hireai.infrastructure.messaging;

import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * D32 retention pass over SourceSnapshots: each snapshot is one {@code applyRetention} call in its own
 * transaction (row-locked, so it never races a booking attaching the same document), and one failure
 * never stops the pass.
 */
@Slf4j
@Component
@Profile("!test")
public class SourceRetentionSweeper {

    private final SourceSnapshotAppService sourceSnapshotAppService;

    public SourceRetentionSweeper(SourceSnapshotAppService sourceSnapshotAppService) {
        this.sourceSnapshotAppService = sourceSnapshotAppService;
    }

    @Scheduled(fixedDelayString = "${hireai.source.retention-sweep-interval:PT1H}")
    public void scheduledSweep() {
        sweep();
    }

    void sweep() {
        List<UUID> candidates = sourceSnapshotAppService.retentionCandidateIds();
        int retired = 0;
        for (UUID id : candidates) {
            try {
                if (sourceSnapshotAppService.applyRetention(id)) {
                    retired++;
                }
            } catch (Exception e) {
                log.warn("Source retention: snapshot {} failed unexpectedly", id, e);
            }
        }
        if (retired > 0) log.info("Source retention: retired {} document(s)", retired);
    }
}
