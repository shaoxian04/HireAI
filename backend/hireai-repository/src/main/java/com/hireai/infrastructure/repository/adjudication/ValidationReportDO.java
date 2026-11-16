package com.hireai.infrastructure.repository.adjudication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** JPA persistence entity for a validation_reports row. */
@Entity
@Table(name = "validation_reports")
public class ValidationReportDO {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(name = "verdict", nullable = false)
    private String verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", columnDefinition = "jsonb", nullable = false)
    private String checks; // JSON array of {rule, passed, detail, outcome}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "findings", columnDefinition = "jsonb", nullable = false)
    private String findings; // JSON array of {code, detail}

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "anomalies", columnDefinition = "jsonb", nullable = false)
    private String anomalies; // JSON array of {check, description}

    protected ValidationReportDO() {
    }

    public ValidationReportDO(UUID id, UUID taskId, int attemptNo, String verdict, String checks,
                              String findings, String anomalies) {
        this.id = id;
        this.taskId = taskId;
        this.attemptNo = attemptNo;
        this.verdict = verdict;
        this.checks = checks;
        this.findings = findings;
        this.anomalies = anomalies;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public int getAttemptNo() { return attemptNo; }
    public String getVerdict() { return verdict; }
    public String getChecks() { return checks; }
    public String getFindings() { return findings; }
    public String getAnomalies() { return anomalies; }
}
