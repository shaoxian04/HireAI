package com.hireai.controller.biz.adjudication;

import com.hireai.controller.biz.adjudication.dto.ValidationReportDTO;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

/** Maps the ValidationReport aggregate to its read DTO. */
public final class ValidationReport2DTOConverter {

    private ValidationReport2DTOConverter() {
    }

    public static ValidationReportDTO toDTO(ValidationReportModel m) {
        return new ValidationReportDTO(
                m.verdict().name(),
                m.checks().stream()
                        .map(c -> new ValidationReportDTO.CheckDTO(c.rule(), c.passed(), c.detail()))
                        .toList());
    }
}
