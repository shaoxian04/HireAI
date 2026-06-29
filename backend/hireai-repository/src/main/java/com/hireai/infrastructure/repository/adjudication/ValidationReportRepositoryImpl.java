package com.hireai.infrastructure.repository.adjudication;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.adjudication.enums.Verdict;
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
        jpa.save(new ValidationReportDO(r.id(), r.taskId(), r.attemptNo(), r.verdict().name(), writeChecks(r.checks())));
        return r;
    }

    @Override
    public Optional<ValidationReportModel> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo) {
        return jpa.findByTaskIdAndAttemptNo(taskId, attemptNo).map(this::toModel);
    }

    private ValidationReportModel toModel(ValidationReportDO d) {
        return ValidationReportModel.rehydrate(d.getId(), d.getTaskId(), d.getAttemptNo(),
                Verdict.valueOf(d.getVerdict()), readChecks(d.getChecks()));
    }

    private String writeChecks(List<CheckResult> checks) {
        try {
            return mapper.writeValueAsString(checks);
        } catch (Exception e) {
            throw new IllegalStateException("serialize checks", e);
        }
    }

    private List<CheckResult> readChecks(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<CheckResult>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("deserialize checks", e);
        }
    }
}
