package com.hireai.domain.biz.task.item.info;

import com.hireai.domain.biz.task.routing.info.ReworkRequest;

import java.util.UUID;

/**
 * One Item as the WorkOrder carries it to the Agent: its number and its input, plus, for an Item
 * whose input is a SourceSnapshot (Engagements only), the signed download link to it ({@code source},
 * null otherwise). {@code itemId} and {@code sourceSnapshotId} are the platform's own references,
 * used to sign the link; the WorkOrder body does not carry them. {@code rework} is set when this Item
 * comes back because a deterministic check rejected its last result (spec S5): which rework it is and
 * the checks that failed; null on its first dispatch.
 */
public record DispatchItem(int itemNo, String input, UUID itemId, UUID sourceSnapshotId, DispatchSource source,
                           ReworkRequest rework) {

    public DispatchItem(int itemNo, String input, UUID itemId, UUID sourceSnapshotId, DispatchSource source) {
        this(itemNo, input, itemId, sourceSnapshotId, source, null);
    }

    public DispatchItem(int itemNo, String input) {
        this(itemNo, input, null, null, null, null);
    }

    public DispatchItem withSource(DispatchSource signed) {
        return new DispatchItem(itemNo, input, itemId, sourceSnapshotId, signed, rework);
    }
}
