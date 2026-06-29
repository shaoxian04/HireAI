package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.adjudication.validation.ValidationAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ValidationAppServiceImpl implements ValidationAppService {

    private final ValidationDomainService validationDomainService;
    private final ValidationReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final SettlementWriteAppService settlementWriteAppService;

    private static final int FIRST_ATTEMPT = 1; // retry attempts arrive in Plan 2

    @Override
    public TaskModel validateAndGate(TaskModel task) {
        ValidationReportModel report =
                validationDomainService.validate(task.outputSpec(), task.result(), FIRST_ATTEMPT);
        reportRepository.save(report);

        if (report.isPass()) {
            TaskModel gated = task.passValidation();
            taskRepository.save(gated);
            log.info("Task {} passed validation -> PENDING_REVIEW", task.id());
            return gated;
        }
        TaskModel gated = task.failValidation();
        taskRepository.save(gated);
        settlementWriteAppService.settleRejected(task.id(), task.clientId(), task.budget());
        log.info("Task {} failed validation -> SPEC_VIOLATION (refunded)", task.id());
        return gated;
    }
}
