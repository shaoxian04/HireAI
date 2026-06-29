package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.lang.NonNull;

public interface ValidationAppService {
    TaskModel validateAndGate(@NonNull TaskModel task);
}
