package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read use case for the automated validation outcome. Ownership-agnostic: the caller (controller)
 * enforces task ownership before invoking this. Returns the latest report for the task, or empty.
 */
@Validated
public interface ValidationReadAppService {
    Optional<ValidationReportModel> latestForTask(@NonNull UUID taskId);

    /** Every report for the task, first attempt first (empty when it was never validated). */
    List<ValidationReportModel> historyForTask(@NonNull UUID taskId);

    /** How many reworks a rejected result gets (D17, {@code hireai.acceptance.max-reworks}). */
    int maxReworks();
}
