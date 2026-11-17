package com.hireai.infrastructure.repository.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task_results rows. Internal to infrastructure. */
public interface TaskResultJpaRepository extends JpaRepository<TaskResultDO, UUID> {

    Optional<TaskResultDO> findByTaskId(UUID taskId);

    /**
     * Replaces a task's delivered result with the Agent's rework (#98) or revision (#95). The row takes
     * the new result's id, the id its DeliverySnapshot is keyed by, so every reader finds the current
     * result's frozen file; the replaced result's snapshot stays on record under its own id, and its
     * content is kept on the rejection round it answered.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE task_results SET id = :id, result_payload = :payload, result_url = :url, agent_status = :status,
                   received_at = :receivedAt, gmt_modified = now()
             WHERE task_id = :taskId
            """, nativeQuery = true)
    int replaceResult(@Param("taskId") UUID taskId, @Param("id") UUID id, @Param("payload") String payload, @Param("url") String url,
                      @Param("status") String status, @Param("receivedAt") Instant receivedAt);
}
