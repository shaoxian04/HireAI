package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;

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
}
