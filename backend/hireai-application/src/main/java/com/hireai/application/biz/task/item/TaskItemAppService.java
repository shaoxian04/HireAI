package com.hireai.application.biz.task.item;

import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.shared.model.Money;

import java.util.List;
import java.util.UUID;

/**
 * The Items of a multi-Item Task (spec S3: D3, D14, D22, D27). A Task without Items is today's
 * single-deliverable Task and never reaches this service's write methods.
 *
 * <p>Every write runs while the caller holds (or this service takes) the Task row lock, so the Task
 * row serializes its Items: an Item settles once, and the Task resolves once.
 */
public interface TaskItemAppService {

    /** Creates the Items of a Task being submitted, each priced at {@code price} (same transaction). */
    void createForSubmit(UUID taskId, List<String> inputs, Money price);

    /** Whether the Task has Items (is a multi-Item Task). */
    boolean hasItems(UUID taskId);

    /**
     * The Agent's COMPLETED callback for a multi-Item Task (caller holds the row lock, the Task is in
     * flight): records each Item's result, runs the Contract's checks on each, refunds the Items that
     * failed or were left out, and moves the Task on: PENDING_REVIEW when any Item passed (API tasks
     * settle at once), else SPEC_VIOLATION. Returns the Task as saved.
     */
    TaskModel recordItemResults(TaskModel inFlight, AgentResultInfo result);

    /** The Client accepts one Item awaiting review (ownership checked; row-locked). */
    void acceptItem(UUID taskId, UUID clientId, int itemNo);

    /**
     * Accepts every Item still awaiting review and resolves the Task (caller holds the row lock).
     * {@code automatically}: the review window closed (or the API channel settles) rather than the
     * Client clicking.
     */
    void acceptAllAwaitingReview(TaskModel pendingReview, boolean automatically);

    /** The Items of one Task, in Item order; empty for a single-deliverable Task. */
    TaskItems itemsOf(UUID taskId);
}
