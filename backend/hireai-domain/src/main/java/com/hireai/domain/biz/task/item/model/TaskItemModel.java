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
 */
public record TaskItemModel(UUID id, UUID taskId, int itemNo, String input, Money price, ItemStatus status,
                            String agentStatus, String resultPayload, String resultUrl,
                            List<ItemCheck> checks, boolean autoAccepted, UUID sourceSnapshotId) {

    public TaskItemModel {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    static TaskItemModel create(UUID taskId, int itemNo, String input, Money price, UUID sourceSnapshotId) {
        return new TaskItemModel(UUID.randomUUID(), taskId, itemNo, input, price, ItemStatus.AWAITING_RESULT,
                null, null, null, List.of(), false, sourceSnapshotId);
    }

    /**
     * AWAITING_RESULT → PENDING_REVIEW when every check passed, else SPEC_VIOLATION (refunded).
     */
    public TaskItemModel recordResult(String agentStatus, String payload, String url, List<ItemCheck> checks) {
        requireStatus(ItemStatus.AWAITING_RESULT, "recordResult");
        requireHttpsResultUrl(itemNo, url);
        boolean passed = checks != null && !checks.isEmpty() && checks.stream().allMatch(ItemCheck::passed);
        return new TaskItemModel(id, taskId, itemNo, input, price,
                passed ? ItemStatus.PENDING_REVIEW : ItemStatus.SPEC_VIOLATION,
                agentStatus, payload, url, checks, false, sourceSnapshotId);
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
        return new TaskItemModel(id, taskId, itemNo, input, price, ItemStatus.FAILED,
                agentStatus, null, null, List.of(), false, sourceSnapshotId);
    }

    /** PENDING_REVIEW → ACCEPTED: the Client accepted it. */
    public TaskItemModel accept() {
        requireStatus(ItemStatus.PENDING_REVIEW, "accept");
        return new TaskItemModel(id, taskId, itemNo, input, price, ItemStatus.ACCEPTED,
                agentStatus, resultPayload, resultUrl, checks, false, sourceSnapshotId);
    }

    /** PENDING_REVIEW → ACCEPTED because the review window closed (or the API channel settles at once). */
    public TaskItemModel acceptAutomatically() {
        TaskItemModel accepted = accept();
        return new TaskItemModel(id, taskId, itemNo, input, price, accepted.status(),
                agentStatus, resultPayload, resultUrl, checks, true, sourceSnapshotId);
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
