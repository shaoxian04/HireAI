package com.hireai.domain.biz.task.sampling.enums;

/**
 * How one Item stands for the error rate (D19, D17′): {@code PASS} once accepted, {@code DEFECT} once
 * refunded for being wrong (conceded, or a revision that failed its checks), {@code OPEN} while it
 * awaits the reviewer or is on its rejection response path.
 */
public enum SampleItemOutcome {
    PASS,
    DEFECT,
    OPEN
}
