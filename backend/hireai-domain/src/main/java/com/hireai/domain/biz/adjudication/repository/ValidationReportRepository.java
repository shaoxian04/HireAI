package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the ValidationReport aggregate (one report per task + attempt). */
public interface ValidationReportRepository {

    ValidationReportModel save(ValidationReportModel report);

    Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);
}
