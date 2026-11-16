package com.hireai.application.biz.adjudication.validation;

import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.task.model.TaskModel;
import org.springframework.lang.NonNull;

import java.util.List;

/**
 * The acceptance gate (spec S5), run on every result as it arrives (RESULT_RECEIVED): the Contract's
 * checks decide between the Client's review (PASS or REVIEW), a rework by the same Agent, and, after
 * the last rework, SPEC_VIOLATION with a refund (D17).
 */
public interface ValidationAppService {

    TaskModel validateAndGate(@NonNull TaskModel task);

    /** As {@link #validateAndGate(TaskModel)}, with the anomalies the Agent reported with the result (D7). */
    TaskModel validateAndGate(@NonNull TaskModel task, @NonNull List<SelfReportedAnomaly> anomalies);
}
