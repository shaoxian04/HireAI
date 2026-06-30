package com.hireai.application.biz.adjudication;

import com.hireai.application.biz.adjudication.validation.impl.ValidationAppServiceImpl;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.enums.Verdict;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.enums.OutputFormat;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ValidationAppServiceImplTest {

    private final ValidationDomainService domain = mock(ValidationDomainService.class);
    private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final SettlementWriteAppService settlement = mock(SettlementWriteAppService.class);
    private final ValidationAppServiceImpl svc = new ValidationAppServiceImpl(domain, reports, tasks, settlement);

    /** Build a RESULT_RECEIVED TaskModel via the canonical transition chain. */
    private TaskModel resultReceivedTask() {
        TaskModel submitted = TaskModel.submit(
                UUID.randomUUID(), "title", "description",
                Money.of("50.00"),
                new OutputSpec(OutputFormat.TEXT, null, "non-empty text"),
                "general");
        TaskResultModel resultModel = TaskResultModel.record(
                submitted.id(), "COMPLETED", "\"hello world\"", null);
        return submitted
                .assignAndQueue(UUID.randomUUID())
                .markExecuting()
                .recordResult(resultModel);
    }

    @Test
    void passMovesToPendingReviewNoRefund() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.validate(any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", true, "non-empty text"))));

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.PENDING_REVIEW);
        verify(reports).save(any());
        verify(tasks).save(any());
        verify(settlement, never()).settleRejected(any(), any(), any());
    }

    @Test
    void failMovesToSpecViolationAndRefunds() {
        TaskModel task = resultReceivedTask();
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(domain.validate(any(), any(), eq(1)))
                .thenReturn(ValidationReportModel.of(task.id(), 1,
                        List.of(new CheckResult("FORMAT_TEXT_NON_EMPTY", false, "empty payload"))));

        TaskModel gated = svc.validateAndGate(task);

        assertThat(gated.status()).isEqualTo(TaskStatus.SPEC_VIOLATION);
        verify(reports).save(any());
        verify(tasks).save(any());
        verify(settlement).settleRejected(eq(task.id()), eq(task.clientId()), eq(task.budget()));
    }
}
