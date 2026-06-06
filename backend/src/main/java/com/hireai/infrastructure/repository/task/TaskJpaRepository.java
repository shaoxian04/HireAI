package com.hireai.infrastructure.repository.task;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task rows. Internal to infrastructure. */
public interface TaskJpaRepository extends JpaRepository<TaskJpaEntity, UUID> {

    List<TaskJpaEntity> findByClientIdOrderByGmtCreateDesc(UUID clientId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskJpaEntity t where t.id = :id")
    Optional<TaskJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
