package com.hireai.domain.biz.task.item.info;

import java.util.UUID;

/**
 * One Item as a Client submits it: its text input and, under an Engagement only (ADR 0010), the
 * SourceSnapshot it is judged against ({@code sourceSnapshotId}, null for a text-only Item).
 */
public record ItemInput(String input, UUID sourceSnapshotId) {

    public static ItemInput text(String input) {
        return new ItemInput(input, null);
    }

    public boolean hasSource() {
        return sourceSnapshotId != null;
    }
}
