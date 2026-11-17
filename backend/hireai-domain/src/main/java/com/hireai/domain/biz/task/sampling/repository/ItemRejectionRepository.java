package com.hireai.domain.biz.task.sampling.repository;

import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rejection rounds of single Items (spec D17′), one row per round per Item. Callers hold the Task's
 * row lock while they change a round.
 */
public interface ItemRejectionRepository {

    void save(ItemRejectionModel rejection);

    /** The Item's latest round, if the reviewer ever rejected it. */
    Optional<ItemRejectionModel> findLatest(UUID itemId);

    /** Every round of a Task's Items, oldest first. */
    List<ItemRejectionModel> findByTaskId(UUID taskId);

    /** Item ids whose latest round awaits the Agent and whose response window closed by {@code now}. */
    List<UUID> findItemIdsResponseExpired(Instant now);

    /** Item ids whose latest round awaits the buyer's decision and whose window closed by {@code now}. */
    List<UUID> findItemIdsDecisionExpired(Instant now);

    /** Item ids whose latest round's notice is undelivered, tried fewer than {@code maxAttempts}, still open. */
    List<UUID> findItemIdsNoticePending(Instant now, int maxAttempts);

    void recordNoticeAttempt(UUID roundId, Instant deliveredAt);

    /** Keeps the result a revision replaced, on the round it answered. */
    void recordReplacedResult(UUID roundId, String resultPayload, String resultUrl);
}
