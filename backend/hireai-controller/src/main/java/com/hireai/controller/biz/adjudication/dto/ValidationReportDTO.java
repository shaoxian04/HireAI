package com.hireai.controller.biz.adjudication.dto;

import java.util.List;

/**
 * Read view of a task's automated acceptance (spec S5): the latest attempt's verdict
 * ({@code PASS}, {@code REVIEW} or {@code FAIL}) and checks, each with its {@code outcome}
 * ({@code PASS}, {@code REJECT}, {@code REVIEW}); the Findings reported beside it (never part of
 * the verdict, never moving money); the anomalies the Agent self-reported with that result;
 * {@code rejectedAttempts} (how many results a check rejected) against {@code maxReworks}; and the
 * earlier attempts, each with the checks that failed it.
 */
public record ValidationReportDTO(String verdict, int attemptNo, List<CheckDTO> checks, List<FindingDTO> findings,
                                  List<AnomalyDTO> anomalies, int rejectedAttempts, int maxReworks,
                                  List<AttemptDTO> previousAttempts) {
    public record CheckDTO(String rule, boolean passed, String detail, String outcome) {
    }

    public record FindingDTO(String code, String detail) {
    }

    public record AnomalyDTO(String check, String description) {
    }

    public record AttemptDTO(int attemptNo, String verdict, List<CheckDTO> failedChecks) {
    }
}
