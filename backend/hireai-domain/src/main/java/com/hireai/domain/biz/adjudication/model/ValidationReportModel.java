package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.List;
import java.util.UUID;

/**
 * Adjudication aggregate root: the automated validation outcome for one result attempt. The
 * verdict comes from the checks alone ({@link Verdict}); {@code findings} are reported beside it
 * and never count (D7), and {@code anomalies} are what the Agent self-reported with this result.
 */
public final class ValidationReportModel {

    private final UUID id;
    private final UUID taskId;
    private final int attemptNo;
    private final Verdict verdict;
    private final List<CheckResult> checks;
    private final List<Finding> findings;
    private final List<SelfReportedAnomaly> anomalies;

    private ValidationReportModel(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks,
                                  List<Finding> findings, List<SelfReportedAnomaly> anomalies) {
        this.id = id;
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.verdict = verdict;
        this.checks = List.copyOf(checks);
        this.findings = findings == null ? List.of() : List.copyOf(findings);
        this.anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    public static ValidationReportModel of(UUID taskId, int attemptNo, List<CheckResult> checks) {
        return of(taskId, attemptNo, checks, List.of(), List.of());
    }

    public static ValidationReportModel of(UUID taskId, int attemptNo, List<CheckResult> checks,
                                           List<Finding> findings, List<SelfReportedAnomaly> anomalies) {
        if (checks == null || checks.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A validation report needs at least one check");
        }
        Verdict verdict = checks.stream().anyMatch(CheckResult::rejects) ? Verdict.FAIL
                : checks.stream().anyMatch(CheckResult::sendsToReview) ? Verdict.REVIEW
                : Verdict.PASS;
        return new ValidationReportModel(UUID.randomUUID(), taskId, attemptNo, verdict, checks, findings, anomalies);
    }

    public static ValidationReportModel rehydrate(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks) {
        return rehydrate(id, taskId, attemptNo, verdict, checks, List.of(), List.of());
    }

    public static ValidationReportModel rehydrate(UUID id, UUID taskId, int attemptNo, Verdict verdict,
                                                  List<CheckResult> checks, List<Finding> findings,
                                                  List<SelfReportedAnomaly> anomalies) {
        return new ValidationReportModel(id, taskId, attemptNo, verdict, checks, findings, anomalies);
    }

    /** Every check passed: the result goes to review and, on the API channel, settles at once. */
    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    /** No check rejected, but one could not decide or an anomaly was reported: the Client must look. */
    public boolean needsReview() {
        return verdict == Verdict.REVIEW;
    }

    /** A check rejected the result deterministically (reworked, then refunded; D17). */
    public boolean isRejected() {
        return verdict == Verdict.FAIL;
    }

    /** The checks that rejected the result: what a rework must fix. */
    public List<CheckResult> rejectingChecks() {
        return checks.stream().filter(CheckResult::rejects).toList();
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public int attemptNo() { return attemptNo; }
    public Verdict verdict() { return verdict; }
    public List<CheckResult> checks() { return checks; }
    public List<Finding> findings() { return findings; }
    public List<SelfReportedAnomaly> anomalies() { return anomalies; }
}
