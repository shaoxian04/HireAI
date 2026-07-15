package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;
import java.util.UUID;

/**
 * Read use case for the automated validation outcome. Ownership-agnostic: the caller (controller)
 * enforces task ownership before invoking this. Returns the latest report for the task, or empty.
 */
@Validated
public interface ValidationReadAppService {
    Optional<ValidationReportModel> latestForTask(@NonNull UUID taskId);
}
