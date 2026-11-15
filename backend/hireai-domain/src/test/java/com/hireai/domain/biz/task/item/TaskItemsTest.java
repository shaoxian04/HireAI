package com.hireai.domain.biz.task.item;

import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.model.ItemCheck;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Items of a multi-Item Task (spec S3: D3, D14, D22, D27). */
class TaskItemsTest {

    private final UUID taskId = UUID.randomUUID();
    private static final List<ItemCheck> PASSED = List.of(new ItemCheck("FORMAT_TEXT_NON_EMPTY", true, null));
    private static final List<ItemCheck> FAILED = List.of(new ItemCheck("FORMAT_TEXT_NON_EMPTY", false, "empty"));

    @Test
    void aSubmittedTaskNumbersItsItemsFromOneAndFreezesPriceTimesCount() {
        TaskItems items = TaskItems.submit(taskId, List.of(" invoice A ", "invoice B", "invoice C"), Money.of("2.50"));

        assertThat(items.all()).extracting(TaskItemModel::itemNo).containsExactly(1, 2, 3);
        assertThat(items.all()).extracting(TaskItemModel::input).containsExactly("invoice A", "invoice B", "invoice C");
        assertThat(items.all()).allMatch(i -> i.status() == ItemStatus.AWAITING_RESULT);
        assertThat(TaskItems.escrowFor(Money.of("2.50"), 3)).isEqualTo(Money.of("7.50"));
    }

    @Test
    void aTaskNeedsAtLeastTwoItemsAndAtMostTheLimit() {
        assertThatThrownBy(() -> TaskItems.submit(taskId, List.of("only one"), Money.of("1.00")))
                .isInstanceOf(DomainException.class).hasMessageContaining("at least 2");
        List<String> tooMany = new ArrayList<>(Collections.nCopies(TaskItems.MAX_ITEMS + 1, "x"));
        assertThatThrownBy(() -> TaskItems.submit(taskId, tooMany, Money.of("1.00")))
                .isInstanceOf(DomainException.class).hasMessageContaining("at most " + TaskItems.MAX_ITEMS);
        assertThatThrownBy(() -> TaskItems.submit(taskId, List.of("a", " "), Money.of("1.00")))
                .isInstanceOf(DomainException.class).hasMessageContaining("Item 2");
    }

    @Test
    void eachItemFollowsItsOwnSmallStateMachine() {
        TaskItemModel item = TaskItems.submit(taskId, List.of("a", "b"), Money.of("1.00")).all().get(0);

        TaskItemModel passed = item.recordResult("COMPLETED", "{\"x\":1}", null, PASSED);
        assertThat(passed.status()).isEqualTo(ItemStatus.PENDING_REVIEW);
        assertThat(passed.accept().status()).isEqualTo(ItemStatus.ACCEPTED);
        assertThat(item.recordResult("COMPLETED", "", null, FAILED).status()).isEqualTo(ItemStatus.SPEC_VIOLATION);
        assertThat(item.recordFailure("FAILED").status()).isEqualTo(ItemStatus.FAILED);

        assertThatThrownBy(item::accept).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> passed.recordFailure("FAILED")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> passed.accept().accept()).isInstanceOf(DomainException.class);
    }

    @Test
    void anItemResultLinkMustBeAnAbsoluteHttpsUrl() {
        TaskItemModel item = TaskItems.submit(taskId, List.of("a", "b"), Money.of("1.00")).all().get(0);

        for (String bad : List.of("javascript:alert(1)", "http://files.example/x", "/relative/x", "https://", "data:text/html,x")) {
            assertThatThrownBy(() -> item.recordResult("COMPLETED", "{}", bad, PASSED))
                    .as(bad).isInstanceOf(DomainException.class).hasMessageContaining("https");
        }
        assertThat(item.recordResult("COMPLETED", "{}", "HTTPS://files.example/x", PASSED).resultUrl())
                .isEqualTo("HTTPS://files.example/x");
        assertThat(item.recordResult("COMPLETED", "{}", null, PASSED).resultUrl()).isNull();
    }

    @Test
    void theAcceptedFractionIsAcceptedItemsOverAllItems() {
        TaskItems items = TaskItems.submit(taskId, List.of("a", "b", "c"), Money.of("1.00"));
        List<TaskItemModel> all = items.all();
        TaskItems settled = new TaskItems(List.of(
                all.get(0).recordResult("COMPLETED", "{}", null, PASSED).accept(),
                all.get(1).recordResult("COMPLETED", "", null, FAILED),
                all.get(2).recordFailure("FAILED")));

        assertThat(settled.allSettled()).isTrue();
        assertThat(settled.acceptedCount()).isEqualTo(1);
        assertThat(settled.acceptedFraction()).isEqualByComparingTo(new BigDecimal("0.333"));
        assertThat(settled.allAccepted()).isFalse();
    }

    @Test
    void anItemAwaitingReviewKeepsTheTaskOpen() {
        List<TaskItemModel> all = TaskItems.submit(taskId, List.of("a", "b"), Money.of("1.00")).all();
        TaskItems open = new TaskItems(List.of(
                all.get(0).recordResult("COMPLETED", "{}", null, PASSED),
                all.get(1).recordFailure("FAILED")));

        assertThat(open.allSettled()).isFalse();
        assertThat(open.awaitingReview()).extracting(TaskItemModel::itemNo).containsExactly(1);
        assertThat(open.anyPassed()).isTrue();
    }
}
