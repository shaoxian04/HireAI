package com.hireai.controller.biz.adjudication.dto;

import java.util.List;

/** Read view of a task's automated validation outcome. */
public record ValidationReportDTO(String verdict, List<CheckDTO> checks) {
    public record CheckDTO(String rule, boolean passed, String detail) {
    }
}
