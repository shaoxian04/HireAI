package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the ValidationReport aggregate (one report per task + attempt). */
public interface ValidationReportRepository {

    ValidationReportModel save(ValidationReportModel report);

    Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);

    /** The most recent report for a task (highest attempt), or empty if it was never validated. */
    Optional<ValidationReportModel> findLatestByTaskId(UUID taskId);

    /** Every report for a task, first attempt first. */
    List<ValidationReportModel> findAllByTaskId(UUID taskId);

    /** How many of a task's results a deterministic check rejected (verdict FAIL): the reworks spent, plus one. */
    int countRejectedByTaskId(UUID taskId);
}
