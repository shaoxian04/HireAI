package com.hireai.infrastructure.repository.task;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task rows. Internal to infrastructure. */
public interface TaskJpaRepository extends JpaRepository<TaskDO, UUID> {

    List<TaskDO> findByClientIdOrderByGmtCreateDesc(UUID clientId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskDO t where t.id = :id")
    Optional<TaskDO> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query(value = "UPDATE tasks SET execution_deadline = :deadline WHERE id = :id", nativeQuery = true)
    void stampExecutionDeadline(@Param("id") UUID id, @Param("deadline") Instant deadline);

    @Modifying
    @Query(value = "UPDATE tasks SET pinned_agent_version_id = :versionId WHERE id = :id", nativeQuery = true)
    void pinAgentVersion(@Param("id") UUID id, @Param("versionId") UUID versionId);

    @Query(value = "SELECT id FROM tasks WHERE status = 'AWAITING_CAPACITY'", nativeQuery = true)
    List<UUID> findIdsAwaitingCapacity();

    @Query(value = """
            SELECT id FROM tasks
            WHERE status IN ('QUEUED','EXECUTING')
              AND execution_deadline IS NOT NULL AND execution_deadline < :now
            """, nativeQuery = true)
    List<UUID> findIdsExecutionExpired(@Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE tasks SET match_attempts = match_attempts + 1 WHERE id = :id", nativeQuery = true)
    void incrementMatchAttempts(@Param("id") UUID id);

    @Query(value = "SELECT match_attempts FROM tasks WHERE id = :id", nativeQuery = true)
    int findMatchAttempts(@Param("id") UUID id);

    @Query(value = "SELECT pinned_agent_version_id FROM tasks WHERE id = :id", nativeQuery = true)
    Optional<UUID> findPinnedAgentVersionId(@Param("id") UUID id);
}
