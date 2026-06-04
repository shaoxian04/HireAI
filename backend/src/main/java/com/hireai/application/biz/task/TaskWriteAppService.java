package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Orchestrates task WRITE use cases. The submit use case enforces Hard Invariant #1
 * (escrow before execution): the task is persisted and its budget frozen in the SAME
 * transaction, so a failed freeze rolls the task back — there is no task without a
 * successful escrow freeze. Returns only the task id; callers re-read via the read service.
 */
@Validated
public interface TaskWriteAppService {

    UUID submit(@NonNull TaskSubmitInfo taskSubmitInfo);
}
