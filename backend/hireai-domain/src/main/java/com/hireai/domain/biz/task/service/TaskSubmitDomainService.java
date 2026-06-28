package com.hireai.domain.biz.task.service;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;

/**
 * Domain service for the task SUBMIT state transition. Framework-free; delegates to the
 * aggregate factory, which owns the invariants. The bean is registered in DomainServiceConfig.
 */
public interface TaskSubmitDomainService {

    TaskModel submit(TaskSubmitInfo info);
}
