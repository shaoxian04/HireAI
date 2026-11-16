package com.hireai.domain.biz.task.sampling.enums;

/**
 * Where a multi-Item Task's sampling inspection stands (spec S8, D19).
 * <ul>
 *   <li>{@code SAMPLING}: a sample is out with the buyer's reviewer (round 1, or round 2 after the rework).</li>
 *   <li>{@code REWORKING}: round 1 failed; the whole Task is back with the Agent for its one rework.</li>
 *   <li>{@code ACCEPTED}: a sample passed; the Items still awaiting review were accepted with it.</li>
 *   <li>{@code PRO_RATA}: the resample failed too; the unverified Items settled at the error rate.</li>
 *   <li>{@code WAIVED}: the buyer accepted everything awaiting review, or let the review window close,
 *       before the sample decided.</li>
 * </ul>
 */
public enum InspectionStatus {
    SAMPLING,
    REWORKING,
    ACCEPTED,
    PRO_RATA,
    WAIVED;

    public boolean isFinished() {
        return this == ACCEPTED || this == PRO_RATA || this == WAIVED;
    }
}
