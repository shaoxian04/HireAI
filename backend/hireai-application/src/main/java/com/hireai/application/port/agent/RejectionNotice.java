package com.hireai.application.port.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * What the platform tells an Agent when a Client rejects its result for reason A/B/C (#95, spec
 * D20): which round, why, until when it may respond, whether it may still revise, and where to send
 * its response. The task's own text and frozen output spec ride along, so a revision can be made
 * from the notice alone.
 */
public record RejectionNotice(UUID taskId, UUID agentVersionId, int round, String reasonCategory, String reason,
                              Instant responseDeadline, boolean revisionAllowed, String responseUrl,
                              String title, String description, String outputSpecJson) {

    /** The {@code event} field of the notice body, which tells it apart from a WorkOrder. */
    public static final String EVENT = "task.rejected";
}
