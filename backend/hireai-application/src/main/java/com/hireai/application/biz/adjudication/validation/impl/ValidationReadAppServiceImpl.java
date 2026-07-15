package com.hireai.application.biz.adjudication.validation.impl;

import com.hireai.application.biz.adjudication.validation.ValidationReadAppService;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ValidationReadAppServiceImpl implements ValidationReadAppService {

    private final ValidationReportRepository validationReportRepository;

    @Override
    public Optional<ValidationReportModel> latestForTask(UUID taskId) {
        return validationReportRepository.findLatestByTaskId(taskId);
    }
}
