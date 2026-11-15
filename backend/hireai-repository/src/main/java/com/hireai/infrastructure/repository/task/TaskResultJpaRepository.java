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
     * Replaces a task's delivered result with the Agent's revision (#95). The row keeps its id; the
     * replaced content is kept on the rejection round it answered.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE task_results SET result_payload = :payload, result_url = :url, agent_status = :status,
                   received_at = :receivedAt, gmt_modified = now()
             WHERE task_id = :taskId
            """, nativeQuery = true)
    int replaceResult(@Param("taskId") UUID taskId, @Param("payload") String payload, @Param("url") String url,
                      @Param("status") String status, @Param("receivedAt") Instant receivedAt);
}
