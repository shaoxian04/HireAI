package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.UUID;

/**
 * One deliverable unit inside a multi-Item Task (spec S3, D3/D14), accepted and settled on its own.
 * Immutable: every transition returns a new copy and an illegal one throws. {@code price} is this
 * Item's share of the Task's escrow (the per-Item price); {@code checks} are the Contract's checks
 * as run against this Item's result (empty until a result arrives). {@code sourceSnapshotId} is the
 * SourceSnapshot the Item's input is (spec S4, D30), only under an Engagement; null for a text Item.
 * {@code paidAmount} is the gross a PRO_RATA Item was paid (spec S8, D19); null otherwise.
 *
 * <p>Sampling (spec S8, D17'): a reviewer's rejection moves a PENDING_REVIEW Item to REJECTED, where it
 * takes the rejection response path: the Agent revises it (back to PENDING_REVIEW, or SPEC_VIOLATION if
 * the revision fails its checks), concedes it (CONCEDED, refunded), or stands by it and the buyer accepts
 * it (ACCEPTED). A failed sample sends every Item still awaiting review back for the whole-Task rework
 * (AWAITING_RESULT); a failed resample settles them PRO_RATA.
 */
public record TaskItemModel(UUID id, UUID taskId, int itemNo, String input, Money price, ItemStatus status,
                            String agentStatus, String resultPayload, String resultUrl,
                            List<ItemCheck> checks, boolean autoAccepted, UUID sourceSnapshotId, Money paidAmount) {

    public TaskItemModel {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    static TaskItemModel create(UUID taskId, int itemNo, String input, Money price, UUID sourceSnapshotId) {
        return new TaskItemModel(UUID.randomUUID(), taskId, itemNo, input, price, ItemStatus.AWAITING_RESULT,
                null, null, null, List.of(), false, sourceSnapshotId, null);
    }

    /**
     * AWAITING_RESULT → PENDING_REVIEW when every check passed, else SPEC_VIOLATION (refunded).
     */
    public TaskItemModel recordResult(String agentStatus, String payload, String url, List<ItemCheck> checks) {
        requireStatus(ItemStatus.AWAITING_RESULT, "recordResult");
        requireHttpsResultUrl(itemNo, url);
        boolean passed = checks != null && !checks.isEmpty() && checks.stream().allMatch(ItemCheck::passed);
        return with(passed ? ItemStatus.PENDING_REVIEW : ItemStatus.SPEC_VIOLATION,
                agentStatus, payload, url, checks, false, null);
    }

    /**
     * Refuses (VALIDATION_ERROR) an Item result link that is not an absolute https URL: the link is
     * shown to the Client, so a {@code javascript:} or plain-http link must never be stored.
     */
    public static void requireHttpsResultUrl(int itemNo, String url) {
        if (url == null) {
            return;
        }
        boolean ok;
        try {
            URI uri = new URI(url.trim());
            ok = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            ok = false;
        }
        if (!ok) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "items: Item " + itemNo + "'s resultUrl must be an absolute https URL");
        }
    }

    /** AWAITING_RESULT → FAILED: the Agent reported this Item failed, or delivered nothing for it. */
    public TaskItemModel recordFailure(String agentStatus) {
        requireStatus(ItemStatus.AWAITING_RESULT, "recordFailure");
        return with(ItemStatus.FAILED, agentStatus, null, null, List.of(), false, null);
    }

    /** PENDING_REVIEW → ACCEPTED: the Client accepted it. */
    public TaskItemModel accept() {
        requireStatus(ItemStatus.PENDING_REVIEW, "accept");
        return with(ItemStatus.ACCEPTED, agentStatus, resultPayload, resultUrl, checks, false, null);
    }

    /** PENDING_REVIEW → ACCEPTED because the review window closed (or the API channel settles at once). */
    public TaskItemModel acceptAutomatically() {
        TaskItemModel accepted = accept();
        return with(accepted.status(), agentStatus, resultPayload, resultUrl, checks, true, null);
    }

    /** PENDING_REVIEW -> REJECTED: the buyer's reviewer found it wrong; its rejection response path opens. */
    public TaskItemModel rejectByReviewer() {
        requireStatus(ItemStatus.PENDING_REVIEW, "rejectByReviewer");
        return with(ItemStatus.REJECTED, agentStatus, resultPayload, resultUrl, checks, false, null);
    }

    /** REJECTED -> CONCEDED: the Agent conceded the reviewer's rejection; the Item is refunded. */
    public TaskItemModel concede() {
        requireStatus(ItemStatus.REJECTED, "concede");
        return with(ItemStatus.CONCEDED, agentStatus, resultPayload, resultUrl, checks, false, null);
    }

    /**
     * REJECTED -> ACCEPTED: the Agent stood by its result and the buyer accepted it ({@code silently}:
     * the buyer let the decision window close, which counts as acceptance).
     */
    public TaskItemModel acceptAfterStandBy(boolean silently) {
        requireStatus(ItemStatus.REJECTED, "acceptAfterStandBy");
        return with(ItemStatus.ACCEPTED, agentStatus, resultPayload, resultUrl, checks, silently, null);
    }

    /**
     * REJECTED -> PENDING_REVIEW with the Agent's revised result when it passes the checks (the reviewer
     * judges it again), else SPEC_VIOLATION (refunded).
     */
    public TaskItemModel revise(String payload, String url, List<ItemCheck> newChecks) {
        requireStatus(ItemStatus.REJECTED, "revise");
        requireHttpsResultUrl(itemNo, url);
        boolean passed = newChecks != null && !newChecks.isEmpty() && newChecks.stream().allMatch(ItemCheck::passed);
        return with(passed ? ItemStatus.PENDING_REVIEW : ItemStatus.SPEC_VIOLATION,
                "COMPLETED", payload, url, newChecks, false, null);
    }

    /** PENDING_REVIEW -> AWAITING_RESULT: the sample failed and the whole Task goes back for its one rework. */
    public TaskItemModel returnForRework() {
        requireStatus(ItemStatus.PENDING_REVIEW, "returnForRework");
        return with(ItemStatus.AWAITING_RESULT, null, null, null, List.of(), false, null);
    }

    /**
     * PENDING_REVIEW -> PRO_RATA: the resample failed too, and this unverified Item is paid {@code paid}
     * (its price x (1 - the error rate)): less than its price, and nothing at all when every sampled Item was wrong.
     */
    public TaskItemModel settleProRata(Money paid) {
        requireStatus(ItemStatus.PENDING_REVIEW, "settleProRata");
        if (paid == null || paid.isNegative() || !price.isGreaterThan(paid)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Item " + itemNo + ": a pro-rata payment is at least 0 and less than its price " + price
                            + "; got " + paid);
        }
        return with(ItemStatus.PRO_RATA, agentStatus, resultPayload, resultUrl, checks, false, paid);
    }

    private TaskItemModel with(ItemStatus next, String nextAgentStatus, String payload, String url,
                               List<ItemCheck> nextChecks, boolean auto, Money paid) {
        return new TaskItemModel(id, taskId, itemNo, input, price, next, nextAgentStatus, payload, url,
                nextChecks, auto, sourceSnapshotId, paid);
    }

    public boolean isSettled() {
        return status.isSettled();
    }

    private void requireStatus(ItemStatus expected, String transition) {
        if (status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Item " + itemNo + ": illegal transition " + transition + " from " + status
                            + "; expected " + expected);
        }
    }
}
