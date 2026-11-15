package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.enums.RejectReason;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Client review of a PENDING_REVIEW task: accept (escrow -> builder payout net of
 * commission) or reject (category-gated: D charges 85/15, A/B/C opens a dispute).
 * Settlement is synchronous and atomic with the task transition; the TaskModel state guard
 * makes it exactly-once. A result left unreviewed past its review window is accepted on the
 * Client's behalf by the review-window sweeper (#94).
 */
@Validated
public interface TaskReviewAppService {

    UUID accept(@NonNull UUID taskId, @NonNull UUID clientId);

    /**
     * Rejects a result awaiting review. Refused with DOMAIN_RULE_VIOLATION once the review window
     * has closed: the Client's silence already counted as acceptance (#94).
     */
    /**
     * The Client accepts one Item of a multi-Item Task awaiting review (spec S3). Settles that Item
     * 85/15; the Task resolves once its last Item settles. NOT_FOUND for another client's Task.
     */
    void acceptItem(UUID taskId, UUID clientId, int itemNo);

    UUID reject(@NonNull UUID taskId, @NonNull UUID clientId, RejectReason reasonCategory, String reason);

    /** Ids still PENDING_REVIEW whose review window has closed by the app's Clock (the sweeper's read). */
    List<UUID> reviewExpiredTaskIds();

    /**
     * Accepts and settles one result whose review window closed unreviewed, exactly as a Client's
     * accept would (spec D10). Row-locked and idempotent: a no-op when the task was reviewed or
     * auto-accepted meanwhile, or its window has not closed by the app's Clock.
     */
    void autoAcceptOne(@NonNull UUID taskId);
}
