package com.hireai.domain.biz.task.item.info;

import java.util.UUID;

/**
 * One Item as the WorkOrder carries it to the Agent: its number and its input, plus, for an Item
 * whose input is a SourceSnapshot (Engagements only), the signed download link to it ({@code source},
 * null otherwise). {@code itemId} and {@code sourceSnapshotId} are the platform's own references,
 * used to sign the link; the WorkOrder body does not carry them.
 */
public record DispatchItem(int itemNo, String input, UUID itemId, UUID sourceSnapshotId, DispatchSource source) {

    public DispatchItem(int itemNo, String input) {
        this(itemNo, input, null, null, null);
    }

    public DispatchItem withSource(DispatchSource signed) {
        return new DispatchItem(itemNo, input, itemId, sourceSnapshotId, signed);
    }
}
