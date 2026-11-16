package com.hireai.domain.biz.acceptance.check.model;

import java.util.List;
import java.util.UUID;

/**
 * What a Finding check needs from outside the Task (spec D7), gathered by the application layer:
 * the same Client's earlier Tasks with the very same brief. Only Findings may read these, so
 * nothing outside the Task ever moves money.
 */
public record FindingFacts(List<UUID> earlierTasksWithSameBrief) {

    public static final FindingFacts NONE = new FindingFacts(List.of());

    public FindingFacts {
        earlierTasksWithSameBrief = earlierTasksWithSameBrief == null ? List.of() : List.copyOf(earlierTasksWithSameBrief);
    }
}
