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
}
