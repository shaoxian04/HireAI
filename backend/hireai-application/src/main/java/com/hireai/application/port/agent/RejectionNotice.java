package com.hireai.application.port.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * What the platform tells an Agent when a Client rejects its result for reason A/B/C (#95, spec
 * D20): which round, why, until when it may respond, whether it may still revise, and where to send
 * its response. The task's own text, its frozen Contract ({@code contractJson}, as the WorkOrder carries
 * it) and the Contract's description ride along, so a revision can be made from the notice alone.
 *
 * <p>A buyer's reviewer rejecting one Item of a multi-Item Task (spec S8, D17′) sends the same notice
 * with {@link #ITEM_EVENT} and {@code itemNo}, the Item's input and the result being rejected set; for a
 * whole-Task rejection they are null.
 */
public record RejectionNotice(UUID taskId, UUID agentVersionId, int round, String reasonCategory, String reason,
                              Instant responseDeadline, boolean revisionAllowed, String responseUrl,
                              String title, String description, String outputSpecJson,
                              Integer itemNo, String itemInput, String itemResult, String contractJson) {

    /** The {@code event} field of the notice body, which tells it apart from a WorkOrder. */
    public static final String EVENT = "task.rejected";
    /** The {@code event} of a single Item's rejection. */
    public static final String ITEM_EVENT = "task.item_rejected";

    public RejectionNotice(UUID taskId, UUID agentVersionId, int round, String reasonCategory, String reason,
                           Instant responseDeadline, boolean revisionAllowed, String responseUrl,
                           String title, String description, String outputSpecJson) {
        this(taskId, agentVersionId, round, reasonCategory, reason, responseDeadline, revisionAllowed, responseUrl,
                title, description, outputSpecJson, null, null, null, null);
    }

    /** With the Item fields, without the Contract (callers that predate it). */
    public RejectionNotice(UUID taskId, UUID agentVersionId, int round, String reasonCategory, String reason,
                           Instant responseDeadline, boolean revisionAllowed, String responseUrl,
                           String title, String description, String outputSpecJson,
                           Integer itemNo, String itemInput, String itemResult) {
        this(taskId, agentVersionId, round, reasonCategory, reason, responseDeadline, revisionAllowed, responseUrl,
                title, description, outputSpecJson, itemNo, itemInput, itemResult, null);
    }

    public String event() {
        return itemNo == null ? EVENT : ITEM_EVENT;
    }
}
