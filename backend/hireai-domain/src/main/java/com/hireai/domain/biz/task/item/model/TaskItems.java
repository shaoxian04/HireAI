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
 * The Items of one multi-Item Task, in Item order (spec S3). A Task without Items is today's
 * single-deliverable Task: its one Item is the Task's own result, so the Items list starts at 2.
 * Escrow is frozen once for the Task as the per-Item price × the Item count.
 */
public record TaskItems(List<TaskItemModel> all) {

    /** A Task with fewer Items is an ordinary single-deliverable Task. */
    public static final int MIN_ITEMS = 2;
    public static final int MAX_ITEMS = 1000;
    /** Same limit as a Task description, which is what an Item's input stands in for. */
    public static final int MAX_INPUT_LENGTH = 5000;
    private static final int QUALITY_SCALE = 3;
    /**
     * The lowest per-Item price that still earns a commission: each Item settles on its own and the
     * 15% is rounded per Item, so below 0.04 it rounds to 0.00 and a many-Item Task would pay none.
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
                    "items: a multi-Item Task needs at least " + MIN_ITEMS + " Items; for one, leave items out "
                            + "and describe the work in the description");
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

    public boolean anyPassed() {
        return all.stream().anyMatch(i -> i.status() == ItemStatus.PENDING_REVIEW || i.status() == ItemStatus.ACCEPTED);
    }

    public boolean allSettled() {
        return !all.isEmpty() && all.stream().allMatch(TaskItemModel::isSettled);
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
