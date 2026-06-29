package com.hireai.domain.biz.adjudication.service;

import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskResultModel;

public interface ValidationDomainService {
    ValidationReportModel validate(OutputSpec spec, TaskResultModel result, int attemptNo);
}
