package com.hireai.infrastructure.repository.task.rejection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task_rejections rows. Internal to infrastructure. */
public interface RejectionJpaRepository extends JpaRepository<RejectionDO, UUID> {

    Optional<RejectionDO> findFirstByTaskIdOrderByRoundDesc(UUID taskId);

    List<RejectionDO> findByTaskIdOrderByRoundAsc(UUID taskId);

    // The task status is part of each sweep query, so a round the task has moved past (resolved by
    // a concurrent click, say) is never picked up even if its own columns still look open.

    @Query(value = """
            SELECT r.task_id FROM task_rejections r JOIN tasks t ON t.id = r.task_id
             WHERE t.status = 'AWAITING_AGENT_RESPONSE' AND r.response IS NULL AND r.response_deadline <= :now
            """, nativeQuery = true)
    List<UUID> findTaskIdsResponseExpired(@Param("now") Instant now);

    @Query(value = """
            SELECT r.task_id FROM task_rejections r JOIN tasks t ON t.id = r.task_id
             WHERE t.status = 'AWAITING_CLIENT_DECISION' AND r.response = 'STAND_BY'
               AND r.decision IS NULL AND r.decision_deadline <= :now
            """, nativeQuery = true)
    List<UUID> findTaskIdsDecisionExpired(@Param("now") Instant now);

    @Query(value = """
            SELECT r.task_id FROM task_rejections r JOIN tasks t ON t.id = r.task_id
             WHERE t.status = 'AWAITING_AGENT_RESPONSE' AND r.response IS NULL
               AND r.notice_sent_at IS NULL AND r.notice_attempts < :maxAttempts AND r.response_deadline > :now
            """, nativeQuery = true)
    List<UUID> findTaskIdsNoticePending(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = """
            UPDATE task_rejections SET notice_attempts = notice_attempts + 1,
                   notice_sent_at = COALESCE(notice_sent_at, CAST(:deliveredAt AS timestamptz))
             WHERE id = :id
            """, nativeQuery = true)
    void recordNoticeAttempt(@Param("id") UUID id, @Param("deliveredAt") Instant deliveredAt);

    @Modifying
    @Query(value = "UPDATE task_rejections SET replaced_result_payload = :payload, replaced_result_url = :url "
            + "WHERE id = :id", nativeQuery = true)
    void recordReplacedResult(@Param("id") UUID id, @Param("payload") String payload, @Param("url") String url);
}
