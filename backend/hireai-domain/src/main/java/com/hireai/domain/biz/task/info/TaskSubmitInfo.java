package com.hireai.domain.biz.task.info;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.shared.model.Money;

import java.util.List;
import java.util.UUID;

/**
 * Domain-layer carrier for the submit use case. Assembled by the controller from a
 * validated request plus the server-side client id; passed to the application layer.
 * {@code category} drives routing to a capability-matching agent. {@code deadlineSeconds} is the
 * optional Client deadline, counted from the submit (null = none; #83). {@code contract} is an explicit
 * Contract to freeze (#91, direct booking under the Client's terms); null = the output spec's equivalent.
 *
 * <p>{@code items} are the inputs of a multi-Item Task (spec S3), empty for a single-deliverable
 * Task. With Items, {@code budget} is the per-Item price and the Task freezes {@link #escrow()}.
 */
public record TaskSubmitInfo(UUID clientId, String title, String description,
                             Money budget, OutputSpec outputSpec, String category, Long deadlineSeconds,
                             Contract contract, List<String> items) {

    public TaskSubmitInfo {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public TaskSubmitInfo(UUID clientId, String title, String description,
                          Money budget, OutputSpec outputSpec, String category, Long deadlineSeconds,
                          Contract contract) {
        this(clientId, title, description, budget, outputSpec, category, deadlineSeconds, contract, List.of());
    }

    public TaskSubmitInfo(UUID clientId, String title, String description,
                          Money budget, OutputSpec outputSpec, String category, Long deadlineSeconds) {
        this(clientId, title, description, budget, outputSpec, category, deadlineSeconds, null, List.of());
    }

    public TaskSubmitInfo(UUID clientId, String title, String description,
                          Money budget, OutputSpec outputSpec, String category) {
        this(clientId, title, description, budget, outputSpec, category, null);
    }

    public boolean hasItems() {
        return !items.isEmpty();
    }

    /** What the Task freezes: the budget, or the per-Item price × the Item count. */
    public Money escrow() {
        return hasItems() ? TaskItems.escrowFor(budget, items.size()) : budget;
    }

    /** This submission with the budget replaced by what is frozen (the Task's escrow of record). */
    public TaskSubmitInfo withEscrowAsBudget() {
        return new TaskSubmitInfo(clientId, title, description, escrow(), outputSpec, category,
                deadlineSeconds, contract, items);
    }
}
