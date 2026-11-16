package com.hireai.controller.biz.adjudication;

import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

import java.util.List;

/** Maps a task's ValidationReport history to its read DTO (the latest attempt, plus the earlier ones). */
public final class ValidationReport2DTOConverter {

    private ValidationReport2DTOConverter() {
    }

    public static ValidationReportDTO toDTO(ValidationReportModel m) {
        return toDTO(List.of(m), 0);
    }

    /** {@code history} is first attempt first and not empty. */
    public static ValidationReportDTO toDTO(List<ValidationReportModel> history, int maxReworks) {
        ValidationReportModel m = history.get(history.size() - 1);
        int rejected = (int) history.stream().filter(r -> r.verdict() == Verdict.FAIL).count();
        return new ValidationReportDTO(
                m.verdict().name(),
                m.attemptNo(),
                m.checks().stream().map(ValidationReport2DTOConverter::check).toList(),
                m.findings().stream().map(f -> new ValidationReportDTO.FindingDTO(f.code(), f.detail())).toList(),
                m.anomalies().stream().map(a -> new ValidationReportDTO.AnomalyDTO(a.check(), a.description())).toList(),
                rejected,
                maxReworks,
                history.subList(0, history.size() - 1).stream()
                        .map(r -> new ValidationReportDTO.AttemptDTO(r.attemptNo(), r.verdict().name(),
                                r.checks().stream().filter(c -> !c.passed())
                                        .map(ValidationReport2DTOConverter::check).toList()))
                        .toList());
    }

    private static ValidationReportDTO.CheckDTO check(CheckResult c) {
        return new ValidationReportDTO.CheckDTO(c.rule(), c.passed(), c.detail(), c.outcome().name());
    }
}
