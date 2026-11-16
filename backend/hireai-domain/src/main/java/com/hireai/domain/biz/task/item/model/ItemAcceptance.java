package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;

import java.util.List;

/**
 * What the acceptance engine made of one Item's result (spec S5): its checks, the Findings reported
 * beside them (never counted, D7) and the anomalies the Agent self-reported with it.
 */
public record ItemAcceptance(List<ItemCheck> checks, List<Finding> findings, List<SelfReportedAnomaly> anomalies) {

    public ItemAcceptance {
        checks = checks == null ? List.of() : List.copyOf(checks);
        findings = findings == null ? List.of() : List.copyOf(findings);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    /**
     * An Item's acceptance from the engine's report. An anomaly may hold an Item out of the sampled lot only
     * by softening a failed anomaly-eligible check on it (that check reads REVIEW): the engine's own
     * {@code SELF_REPORTED_ANOMALY} entry, for an anomaly that explains no check, is dropped here, so an
     * Agent that flags every wrong Item (naming nothing, an unknown check, a passing or an ineligible one)
     * changes neither the lot nor the measured error rate. The anomaly itself is kept, for the reviewer.
     */
    public static ItemAcceptance fromReport(ValidationReportModel report) {
        return new ItemAcceptance(report.checks().stream()
                .filter(c -> !CheckResult.SELF_REPORTED_ANOMALY.equals(c.rule()))
                .map(c -> new ItemCheck(c.rule(), c.passed(), c.detail(), c.outcome()))
                .toList(), report.findings(), report.anomalies());
    }
}
