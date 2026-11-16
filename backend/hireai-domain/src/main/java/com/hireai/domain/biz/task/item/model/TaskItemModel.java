package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
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
                            List<ItemCheck> checks, boolean autoAccepted, UUID sourceSnapshotId, Money paidAmount,
                            List<Finding> findings, List<SelfReportedAnomaly> anomalies) {

    public TaskItemModel {
        checks = checks == null ? List.of() : List.copyOf(checks);
        findings = findings == null ? List.of() : List.copyOf(findings);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    public TaskItemModel(UUID id, UUID taskId, int itemNo, String input, Money price, ItemStatus status,
                         String agentStatus, String resultPayload, String resultUrl, List<ItemCheck> checks,
                         boolean autoAccepted, UUID sourceSnapshotId, Money paidAmount) {
        this(id, taskId, itemNo, input, price, status, agentStatus, resultPayload, resultUrl, checks, autoAccepted,
                sourceSnapshotId, paidAmount, List.of(), List.of());
    }

    static TaskItemModel create(UUID taskId, int itemNo, String input, Money price, UUID sourceSnapshotId) {
        return new TaskItemModel(UUID.randomUUID(), taskId, itemNo, input, price, ItemStatus.AWAITING_RESULT,
                null, null, null, List.of(), false, sourceSnapshotId, null);
    }

    /**
     * AWAITING_RESULT → PENDING_REVIEW when no check rejected (a check that sent it to review makes it
     * {@link #needsReview()}), else SPEC_VIOLATION (refunded).
     */
    public TaskItemModel recordResult(String agentStatus, String payload, String url, List<ItemCheck> checks) {
        return recordResult(agentStatus, payload, url, new ItemAcceptance(checks, List.of(), List.of()));
    }

    /** {@link #recordResult(String, String, String, List)} keeping the Findings and anomalies reported with it (D7). */
    public TaskItemModel recordResult(String agentStatus, String payload, String url, ItemAcceptance acceptance) {
        requireStatus(ItemStatus.AWAITING_RESULT, "recordResult");
        requireHttpsResultUrl(itemNo, url);
        return judged(agentStatus, payload, url, acceptance);
    }

    private TaskItemModel judged(String nextAgentStatus, String payload, String url, ItemAcceptance acceptance) {
        List<ItemCheck> newChecks = acceptance.checks();
        boolean passed = !newChecks.isEmpty() && newChecks.stream().noneMatch(ItemCheck::rejects);
        return new TaskItemModel(id, taskId, itemNo, input, price,
                passed ? ItemStatus.PENDING_REVIEW : ItemStatus.SPEC_VIOLATION, nextAgentStatus, payload, url,
                newChecks, false, sourceSnapshotId, null, acceptance.findings(), acceptance.anomalies());
    }

    /**
     * True when a check could not decide, or an anomaly the Agent reported sent this Item to review (D7):
     * the buyer's reviewer judges it on its own (accepted, like any result, if nobody does in the window), so
     * it is held out of the sampled lot within the Task's {@link FlaggedShareCap}.
     */
    public boolean needsReview() {
        return checks.stream().anyMatch(ItemCheck::sendsToReview);
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
        return revise(payload, url, new ItemAcceptance(newChecks, List.of(), List.of()));
    }

    /** {@link #revise(String, String, List)} keeping what was reported with the revision. */
    public TaskItemModel revise(String payload, String url, ItemAcceptance acceptance) {
        requireStatus(ItemStatus.REJECTED, "revise");
        requireHttpsResultUrl(itemNo, url);
        return judged("COMPLETED", payload, url, acceptance);
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
        // A result taken back (the whole-Task rework) also drops what was reported with it.
        boolean sameResult = nextChecks == checks;
        return new TaskItemModel(id, taskId, itemNo, input, price, next, nextAgentStatus, payload, url,
                nextChecks, auto, sourceSnapshotId, paid, sameResult ? findings : List.of(),
                sameResult ? anomalies : List.of());
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
