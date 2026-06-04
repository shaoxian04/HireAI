package com.hireai.domain.biz.task.service;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;

/**
 * Domain service for the task SUBMIT state transition. Stateless and framework-free;
 * delegates to the aggregate factory, which owns the invariants.
 */
public class TaskSubmitDomainService {

    public TaskModel submit(TaskSubmitInfo info) {
        return TaskModel.submit(info.clientId(), info.title(), info.description(),
                info.budget(), info.outputSpec());
    }
}
