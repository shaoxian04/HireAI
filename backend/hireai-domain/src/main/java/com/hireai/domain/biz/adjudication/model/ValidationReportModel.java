package com.hireai.domain.biz.adjudication.model;

import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.util.List;
import java.util.UUID;

/** Adjudication aggregate root: the automated validation outcome for one result attempt. */
public final class ValidationReportModel {

    private final UUID id;
    private final UUID taskId;
    private final int attemptNo;
    private final Verdict verdict;
    private final List<CheckResult> checks;

    private ValidationReportModel(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks) {
        this.id = id;
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.verdict = verdict;
        this.checks = List.copyOf(checks);
    }

    public static ValidationReportModel of(UUID taskId, int attemptNo, List<CheckResult> checks) {
        if (checks == null || checks.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A validation report needs at least one check");
        }
        Verdict verdict = checks.stream().allMatch(CheckResult::passed) ? Verdict.PASS : Verdict.FAIL;
        return new ValidationReportModel(UUID.randomUUID(), taskId, attemptNo, verdict, checks);
    }

    public static ValidationReportModel rehydrate(UUID id, UUID taskId, int attemptNo, Verdict verdict, List<CheckResult> checks) {
        return new ValidationReportModel(id, taskId, attemptNo, verdict, checks);
    }

    public boolean isPass() {
        return verdict == Verdict.PASS;
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public int attemptNo() { return attemptNo; }
    public Verdict verdict() { return verdict; }
    public List<CheckResult> checks() { return checks; }
}
