package com.hireai.domain.biz.task.service.impl;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.service.TaskSubmitDomainService;

/** Stateless implementation of the submit transition; delegates to the aggregate factory. */
public class TaskSubmitDomainServiceImpl implements TaskSubmitDomainService {

    @Override
    public TaskModel submit(TaskSubmitInfo info) {
        return TaskModel.submit(info.clientId(), info.title(), info.description(),
                info.budget(), info.outputSpec(), info.category());
    }
}
