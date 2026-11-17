package com.hireai.domain.biz.acceptance.check.model;

import java.util.List;
import java.util.UUID;

/**
 * What a Finding check needs from outside the Task (spec D7), gathered by the application layer, all
 * from the same Client's own work: its earlier Tasks with the very same brief (image_generation), and
 * for an invoice Item (D45) the earlier Items whose document had the same content hash, or whose
 * extracted supplier tax id and invoice number were the same. Only Findings may read these, so nothing
 * outside the Task ever moves money.
 */
public record FindingFacts(List<UUID> earlierTasksWithSameBrief, List<ItemRef> earlierItemsWithSameDocument,
                           List<ItemRef> earlierItemsWithSameInvoice) {

    /** An Item of one of the Client's Tasks. */
    public record ItemRef(UUID taskId, int itemNo) {
    }

    public static final FindingFacts NONE = new FindingFacts(List.of());

    public FindingFacts {
        earlierTasksWithSameBrief = earlierTasksWithSameBrief == null ? List.of() : List.copyOf(earlierTasksWithSameBrief);
        earlierItemsWithSameDocument = earlierItemsWithSameDocument == null ? List.of()
                : List.copyOf(earlierItemsWithSameDocument);
        earlierItemsWithSameInvoice = earlierItemsWithSameInvoice == null ? List.of()
                : List.copyOf(earlierItemsWithSameInvoice);
    }

    public FindingFacts(List<UUID> earlierTasksWithSameBrief) {
        this(earlierTasksWithSameBrief, List.of(), List.of());
    }
}
