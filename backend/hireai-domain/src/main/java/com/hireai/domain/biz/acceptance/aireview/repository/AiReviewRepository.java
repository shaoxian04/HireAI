package com.hireai.domain.biz.acceptance.aireview.repository;

import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One AI Review per Task (spec S10). */
public interface AiReviewRepository {

    void insert(AiReviewModel review);

    void save(AiReviewModel review);

    Optional<AiReviewModel> findByTaskId(UUID taskId);

    /** The same row, locked FOR UPDATE until the transaction ends. */
    Optional<AiReviewModel> lockByTaskId(UUID taskId);

    /** Task ids of reviews still REQUESTED since before {@code cutoff}: the runner never finished them. */
    List<UUID> findRequestedBefore(Instant cutoff);

    /** Task ids of FROZEN reviews whose Task has ended: no result will be reviewed, so the fee goes back. */
    List<UUID> findFrozenOnEndedTasks();
}
