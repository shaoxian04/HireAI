package com.hireai.domain.biz.task.sampling.model;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One judgement of one Item by the buyer's reviewer (spec S8, D4): the ground truth for sampling comes
 * from a person on the buyer's side, never an LLM. {@code sampled}: the Item was in a sample of this
 * round when judged (only those feed the error rate); otherwise it was an extra check, handled on its
 * own. A FAIL names an A/B/C reason and opens the Item's rejection response path (D17′). Append-only.
 */
public record ItemJudgementModel(UUID id, UUID taskId, UUID itemId, int itemNo, int round, boolean passed,
                                 RejectReason reasonCategory, String reason, boolean sampled,
                                 UUID judgedBy, Instant judgedAt) {

    public static ItemJudgementModel pass(UUID taskId, UUID itemId, int itemNo, int round, boolean sampled,
                                          UUID judgedBy, Instant now) {
        return new ItemJudgementModel(UUID.randomUUID(), taskId, itemId, itemNo, round, true, null, null, sampled,
                judgedBy, now);
    }

    public static ItemJudgementModel fail(UUID taskId, UUID itemId, int itemNo, int round, boolean sampled,
                                          RejectReason reasonCategory, String reason, UUID judgedBy, Instant now) {
        if (reasonCategory == null || !reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "reasonCategory: a wrong Item is rejected for A_MISMATCH, B_FACTUAL or C_INCOMPLETE");
        }
        String trimmed = reason == null || reason.isBlank() ? null : reason.trim();
        if (trimmed == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "reason: say what is wrong with Item " + itemNo + ", so the agent can respond to it");
        }
        if (trimmed.length() > RejectionModel.MAX_REASON_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "reason: at most " + RejectionModel.MAX_REASON_LENGTH + " characters");
        }
        return new ItemJudgementModel(UUID.randomUUID(), taskId, itemId, itemNo, round, false, reasonCategory,
                trimmed, sampled, judgedBy, now);
    }
}
