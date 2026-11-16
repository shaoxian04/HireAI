package com.hireai.domain.biz.acceptance.aireview.model;

import com.hireai.domain.biz.acceptance.aireview.enums.AiReviewStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A Task's AI Review (spec S10, D37, D38): the frozen criteria and fee, and what became of them.
 * Immutable; each transition returns the next state.
 *
 * <ul>
 *   <li>{@link #frozen} at submit, with the fee in escrow;</li>
 *   <li>{@link #request} when a result passed the deterministic checks: {@code decisive} on the
 *       API/MCP channel, where the verdict settles the Task and is final, advisory on the web;</li>
 *   <li>{@link #complete} with a verdict on every criterion (the fee is then charged), or
 *       {@link #recordFailure} until the reviewer has failed {@code maxAttempts} times (FAILED, the
 *       fee is refunded);</li>
 *   <li>{@link #release} when no result will ever be reviewed (the fee is refunded).</li>
 * </ul>
 *
 * <p>The verdict's ruling category is computed here, never by the model (Invariant #3): every
 * criterion passed → FULFILLED, some → PARTIALLY_FULFILLED, none → NOT_FULFILLED.
 */
public record AiReviewModel(UUID taskId, List<String> criteria, Money fee, AiReviewStatus status,
                            boolean decisive, int attempts, List<CriterionVerdict> verdicts, String model,
                            String failureReason, Instant createdAt, Instant requestedAt, Instant completedAt) {

    public AiReviewModel {
        if (taskId == null || criteria == null || criteria.isEmpty() || fee == null || status == null
                || createdAt == null) {
            throw new IllegalArgumentException("An AI Review needs its Task, criteria, fee, status and creation time");
        }
        criteria = List.copyOf(criteria);
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    }

    public static AiReviewModel frozen(UUID taskId, AiReviewTerms terms, Instant now) {
        return new AiReviewModel(taskId, terms.criteria(), terms.fee(), AiReviewStatus.FROZEN, false, 0,
                List.of(), null, null, now, null, null);
    }

    /** FROZEN → REQUESTED: a result passed the deterministic checks and waits for the reviewer. */
    public AiReviewModel request(boolean decisive, Instant now) {
        requireStatus(AiReviewStatus.FROZEN, "request");
        return new AiReviewModel(taskId, criteria, fee, AiReviewStatus.REQUESTED, decisive, 0, List.of(), null,
                null, createdAt, now, null);
    }

    /** REQUESTED → COMPLETED: one verdict per frozen criterion, kept in the criteria's order. */
    public AiReviewModel complete(List<CriterionVerdict> answered, String reviewerModel, Instant now) {
        requireStatus(AiReviewStatus.REQUESTED, "complete");
        Map<String, CriterionVerdict> byCriterion = new HashMap<>();
        for (CriterionVerdict verdict : answered == null ? List.<CriterionVerdict>of() : answered) {
            if (!criteria.contains(verdict.criterion()) || byCriterion.put(verdict.criterion(), verdict) != null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "The AI Review answered a criterion it was not asked, or one twice: " + verdict.criterion());
            }
        }
        List<CriterionVerdict> ordered = new ArrayList<>();
        for (String criterion : criteria) {
            CriterionVerdict verdict = byCriterion.get(criterion);
            if (verdict == null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "The AI Review gave no verdict on: " + criterion);
            }
            ordered.add(verdict);
        }
        return new AiReviewModel(taskId, criteria, fee, AiReviewStatus.COMPLETED, decisive, attempts + 1, ordered,
                reviewerModel, null, createdAt, requestedAt, now);
    }

    /**
     * The reviewer gave no usable verdict, which is never a pass (fail closed). FAILED at once when the
     * failure is not {@code retryable} (an unusable answer), else after {@code maxAttempts}; until then
     * still REQUESTED for a retry.
     */
    public AiReviewModel recordFailure(String reason, boolean retryable, int maxAttempts, Instant now) {
        requireStatus(AiReviewStatus.REQUESTED, "recordFailure");
        int tried = attempts + 1;
        boolean givesUp = !retryable || tried >= maxAttempts;
        return new AiReviewModel(taskId, criteria, fee, givesUp ? AiReviewStatus.FAILED : AiReviewStatus.REQUESTED,
                decisive, tried, List.of(), null, reason, createdAt, requestedAt, givesUp ? now : null);
    }

    /** FROZEN|REQUESTED → RELEASED: nothing will be reviewed (the Task ended, or the Client decided first). */
    public AiReviewModel release(Instant now) {
        if (status != AiReviewStatus.FROZEN && status != AiReviewStatus.REQUESTED) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "An AI Review that is " + status + " cannot be released");
        }
        return new AiReviewModel(taskId, criteria, fee, AiReviewStatus.RELEASED, decisive, attempts, List.of(),
                null, failureReason, createdAt, requestedAt, now);
    }

    /** The verdict's ruling category (D37); only for a COMPLETED review. */
    public RulingCategory rulingCategory() {
        requireStatus(AiReviewStatus.COMPLETED, "rulingCategory");
        long passed = verdicts.stream().filter(CriterionVerdict::passed).count();
        if (passed == verdicts.size()) {
            return RulingCategory.FULFILLED;
        }
        return passed == 0 ? RulingCategory.NOT_FULFILLED : RulingCategory.PARTIALLY_FULFILLED;
    }

    private void requireStatus(AiReviewStatus expected, String action) {
        if (status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Cannot " + action + " an AI Review that is " + status);
        }
    }
}
