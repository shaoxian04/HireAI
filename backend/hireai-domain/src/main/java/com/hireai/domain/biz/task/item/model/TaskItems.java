package com.hireai.domain.biz.task.item.model;

import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.info.ItemInput;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The Items of one Task, in Item order (spec S3: 1..n). A Task without Items is the single-deliverable Task,
 * whose one result is the Task's own.
 * Escrow is frozen once for the Task as the per-Item price × the Item count.
 */
public record TaskItems(List<TaskItemModel> all) {

    /** Spec S3: a Task holds 1..n Items (a single document is a one-Item Task); without Items it is single-deliverable. */
    public static final int MIN_ITEMS = 1;
    public static final int MAX_ITEMS = 1000;
    /** Same limit as a Task description, which is what an Item's input stands in for. */
    public static final int MAX_INPUT_LENGTH = 5000;
    private static final int QUALITY_SCALE = 3;
    /**
     * The lowest per-Item price that still earns a commission: 15% of a lower price rounds to 0.00, so a
     * Task whose only paid Item cost less would pay none. Commission is allocated on the Task's running
     * total ({@code TaskSettlementRecord.commissionOnNextItem}), not rounded up on every Item.
     * Single-deliverable Tasks keep their own minimum (see build-status Known gaps).
     */
    public static final Money MIN_ITEM_PRICE = Money.of("0.04");

    public TaskItems {
        all = all.stream().sorted(Comparator.comparingInt(TaskItemModel::itemNo)).toList();
    }

    /** New text-only Items for a submitted Task, numbered from 1, each priced at {@code price}. */
    public static TaskItems submit(UUID taskId, List<String> inputs, Money price) {
        return submitInputs(taskId, inputs == null ? null : inputs.stream().map(ItemInput::text).toList(), price);
    }

    /**
     * New Items for a submitted Task, numbered from 1, each priced at {@code price}. An Item may reference
     * a SourceSnapshot; whether the Client may use it is the caller's check (Engagements only, ADR 0010).
     */
    public static TaskItems submitInputs(UUID taskId, List<ItemInput> inputs, Money price) {
        if (inputs == null || inputs.size() < MIN_ITEMS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "items: give at least " + MIN_ITEMS + " Item, or leave items out and describe the work "
                            + "in the description");
        }
        if (inputs.size() > MAX_ITEMS) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "items: a Task can hold at most " + MAX_ITEMS + " Items; got " + inputs.size());
        }
        if (price == null || MIN_ITEM_PRICE.isGreaterThan(price)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "budget: with Items, the budget is the price of one Item and must be at least "
                            + MIN_ITEM_PRICE.value() + " credits; got " + (price == null ? null : price.value()));
        }
        List<TaskItemModel> items = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            ItemInput given = inputs.get(i);
            String input = given == null || given.input() == null ? "" : given.input().trim();
            if (input.isEmpty() || input.length() > MAX_INPUT_LENGTH) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "items: Item " + (i + 1) + " must have between 1 and " + MAX_INPUT_LENGTH + " characters");
            }
            items.add(TaskItemModel.create(taskId, i + 1, input, price, given == null ? null : given.sourceSnapshotId()));
        }
        return new TaskItems(items);
    }

    /** What the Task freezes in escrow: the per-Item price times the Item count. */
    public static Money escrowFor(Money price, int itemCount) {
        return Money.of(price.value().multiply(BigDecimal.valueOf(itemCount)));
    }

    /** The distinct SourceSnapshots these Items reference, in Item order. */
    public List<UUID> sourceSnapshotIds() {
        return all.stream().map(TaskItemModel::sourceSnapshotId).filter(Objects::nonNull).distinct().toList();
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }

    public int count() {
        return all.size();
    }

    public List<TaskItemModel> awaitingReview() {
        return all.stream().filter(i -> i.status() == ItemStatus.PENDING_REVIEW).toList();
    }

    /**
     * The Items a passed lot (or the API channel) accepts and pays at once (spec S8): those awaiting review that
     * every check passed. An Item a check sent to review ({@link TaskItemModel#needsReview()}, D7) is never
     * paid without its own judgement: the reviewer judges it, or it is accepted when the review window closes.
     */
    public List<TaskItemModel> lot() {
        return all.stream().filter(i -> i.status() == ItemStatus.PENDING_REVIEW && !i.needsReview()).toList();
    }

    /**
     * The Items the random sample is drawn from, and that a failed lot reworks or settles pro rata (spec S8):
     * the {@link #lot()}, plus every flagged Item beyond the {@code cap}. They share every penalty of the lot,
     * but a passed lot still leaves the flagged ones to their own review. A flagged Item within the cap (the first ones, in Item order) is the reviewer's own
     * extra check and never drawn; one beyond it can be drawn and then counts in the error rate like any
     * sampled Item. So flagging more Items cannot shrink the population, the sample or the measured error
     * rate. The rank counts every flagged Item whatever its status, so judging one never moves another.
     */
    public List<TaskItemModel> sampleable(FlaggedShareCap cap) {
        List<UUID> heldOut = all.stream().filter(TaskItemModel::needsReview).limit(cap.allowed(count()))
                .map(TaskItemModel::id).toList();
        return all.stream().filter(i -> i.status() == ItemStatus.PENDING_REVIEW && !heldOut.contains(i.id()))
                .toList();
    }

    /** The Items awaiting their own judgement because a check sent them to review (D7). */
    public List<TaskItemModel> flaggedForReview() {
        return all.stream().filter(i -> i.status() == ItemStatus.PENDING_REVIEW && i.needsReview()).toList();
    }

    public boolean anyPassed() {
        return all.stream().anyMatch(i -> i.status() == ItemStatus.PENDING_REVIEW || i.status() == ItemStatus.ACCEPTED);
    }

    public boolean allSettled() {
        return !all.isEmpty() && all.stream().allMatch(TaskItemModel::isSettled);
    }

    /** Whether any Item was paid anything (accepted, or pro rata). */
    public boolean anyPaid() {
        return all.stream().anyMatch(i -> i.status() == ItemStatus.ACCEPTED || i.status() == ItemStatus.PRO_RATA);
    }

    /**
     * Whether any Item is paid, or still open on its rejection path: after a whole-Task rework (spec S8)
     * a Task whose reworked Items all failed still has Items that settled, or will, on their own.
     */
    public boolean anyOpenOrPaid() {
        return anyPaid() || all.stream().anyMatch(i -> i.status() == ItemStatus.REJECTED);
    }

    /** The Items still awaiting a result, in Item order. */
    public List<TaskItemModel> awaitingResult() {
        return all.stream().filter(i -> i.status() == ItemStatus.AWAITING_RESULT).toList();
    }

    /**
     * The accepted-Item count the Task's reputation event credits (D22): every accepted Item, plus the
     * pro-rata Items weighted by what they were paid of their price, rounded down.
     */
    public int creditedCount() {
        BigDecimal proRata = all.stream().filter(i -> i.status() == ItemStatus.PRO_RATA)
                .map(i -> i.paidAmount().value().divide(i.price().value(), QUALITY_SCALE, RoundingMode.DOWN))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return acceptedCount() + proRata.setScale(0, RoundingMode.DOWN).intValue();
    }

    public int acceptedCount() {
        return (int) all.stream().filter(i -> i.status() == ItemStatus.ACCEPTED).count();
    }

    public boolean allAccepted() {
        return !all.isEmpty() && acceptedCount() == all.size();
    }

    /**
     * The quality of the Task's one reputation event (D22): accepted Items over all Items, 3dp.
     */
    public BigDecimal acceptedFraction() {
        if (all.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(acceptedCount())
                .divide(BigDecimal.valueOf(all.size()), QUALITY_SCALE, RoundingMode.HALF_UP);
    }

    public TaskItemModel item(int itemNo) {
        return all.stream().filter(i -> i.itemNo() == itemNo).findFirst()
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "No Item " + itemNo));
    }
}
