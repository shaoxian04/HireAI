package com.hireai.application.biz.adjudication;

import com.hireai.application.biz.adjudication.validation.impl.ValidationReadAppServiceImpl;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidationReadAppServiceImplTest {

    private final ValidationReportRepository repo = mock(ValidationReportRepository.class);
    private final ValidationReadAppServiceImpl service = new ValidationReadAppServiceImpl(repo);

    @Test
    void returnsTheLatestReportForTheTask() {
        UUID taskId = UUID.randomUUID();
        ValidationReportModel report = ValidationReportModel.of(taskId, 1,
                List.of(new CheckResult("format", false, "expected FILE, got none")));
        when(repo.findLatestByTaskId(eq(taskId))).thenReturn(Optional.of(report));

        assertThat(service.latestForTask(taskId)).containsSame(report);
    }

    @Test
    void returnsEmptyWhenNoReportExists() {
        UUID taskId = UUID.randomUUID();
        when(repo.findLatestByTaskId(eq(taskId))).thenReturn(Optional.empty());

        assertThat(service.latestForTask(taskId)).isEmpty();
    }
}
