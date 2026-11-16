package com.hireai.application.biz.acceptance.aireview;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewTerms;
import com.hireai.domain.biz.task.model.TaskModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The AI Review use cases (spec S10, D5, D37, D38). The fee is frozen with the Task at submit
 * ({@link #freezeAtSubmit}); a result that passed the deterministic checks requests the review
 * ({@link #requestIfFrozen}); the reviewer runs after that commits ({@link #run}). On the web the
 * verdict is an advisory report beside the Client's own review; on API/MCP it settles the Task
 * deterministically from its ruling category and is final.
 */
public interface AiReviewAppService {

    /** Freezes the AI Review fee in escrow and records the review, in the submit transaction. */
    void freezeAtSubmit(UUID taskId, UUID clientId, AiReviewTerms terms);

    /**
     * Requests the Task's AI Review, in the validation transaction, when one is frozen; true when it
     * was requested. {@code decisive} on the API/MCP channel. The review runs after the commit.
     */
    boolean requestIfFrozen(TaskModel task, boolean decisive);

    /** Runs one requested review: calls the reviewer (no transaction held), then records the verdict. */
    void run(UUID taskId);

    /** Refunds the fee of a review that never ran because the Task ended first; a no-op otherwise. */
    void releaseIfUnused(UUID taskId);

    /** True while a decisive review is still deciding the Task: nobody else may accept or reject it. */
    boolean isDeciding(UUID taskId);

    /** Requested reviews the runner has not finished for a while (a crash, a retry after a failure). */
    List<UUID> staleRequestedTaskIds();

    /** Frozen reviews whose Task has ended without a reviewed result. */
    List<UUID> unusedTaskIds();

    /** The Task's AI Review, for its Client only (Invariant #5); empty when it has none or is not theirs. */
    Optional<AiReviewModel> findForClient(UUID taskId, UUID clientId);
}
