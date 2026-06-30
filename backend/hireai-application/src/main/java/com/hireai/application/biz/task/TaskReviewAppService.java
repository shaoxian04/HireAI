package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.enums.RejectReason;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Client review of a PENDING_REVIEW task: accept (escrow -> builder payout net of
 * commission) or reject (category-gated: D charges 85/15, A/B/C opens a dispute).
 * Settlement is synchronous and atomic with the task transition; the TaskModel state guard
 * makes it exactly-once.
 */
@Validated
public interface TaskReviewAppService {

    UUID accept(@NonNull UUID taskId, @NonNull UUID clientId);

    UUID reject(@NonNull UUID taskId, @NonNull UUID clientId, RejectReason reasonCategory, String reason);
}
