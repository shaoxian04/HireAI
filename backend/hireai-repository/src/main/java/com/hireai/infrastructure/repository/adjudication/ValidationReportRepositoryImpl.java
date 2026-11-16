package com.hireai.infrastructure.repository.adjudication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link ValidationReportRepository}. Jackson lives in infra; checks are serialized as JSONB. */
@Repository
public class ValidationReportRepositoryImpl implements ValidationReportRepository {

    private final ValidationReportJpaRepository jpa;
    private final ObjectMapper mapper = new ObjectMapper();

    public ValidationReportRepositoryImpl(ValidationReportJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ValidationReportModel save(ValidationReportModel r) {
        jpa.save(new ValidationReportDO(r.id(), r.taskId(), r.attemptNo(), r.verdict().name(), write(r.checks()),
                write(r.findings()), write(r.anomalies())));
        return r;
    }

    @Override
    public Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo) {
        return jpa.findByTaskIdAndAttemptNo(taskId, attemptNo).map(this::toModel);
    }

    @Override
    public Optional<ValidationReportModel> findLatestByTaskId(UUID taskId) {
        return jpa.findFirstByTaskIdOrderByAttemptNoDesc(taskId).map(this::toModel);
    }

    @Override
    public List<ValidationReportModel> findAllByTaskId(UUID taskId) {
        return jpa.findByTaskIdOrderByAttemptNoAsc(taskId).stream().map(this::toModel).toList();
    }

    @Override
    public int countRejectedByTaskId(UUID taskId) {
        return jpa.countByTaskIdAndVerdict(taskId, Verdict.FAIL.name());
    }

    private ValidationReportModel toModel(ValidationReportDO d) {
        return ValidationReportModel.rehydrate(d.getId(), d.getTaskId(), d.getAttemptNo(),
                Verdict.valueOf(d.getVerdict()), read(d.getChecks(), new TypeReference<List<CheckResult>>() {}),
                read(d.getFindings(), new TypeReference<List<Finding>>() {}),
                read(d.getAnomalies(), new TypeReference<List<SelfReportedAnomaly>>() {}));
    }

    private String write(List<?> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("serialize validation report", e);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        if (json == null) {
            return List.of();
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize validation report", e);
        }
    }
}
