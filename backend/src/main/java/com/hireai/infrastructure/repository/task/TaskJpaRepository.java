package com.hireai.infrastructure.repository.task;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for task rows. Internal to infrastructure. */
public interface TaskJpaRepository extends JpaRepository<TaskJpaEntity, UUID> {

    List<TaskJpaEntity> findByClientIdOrderByGmtCreateDesc(UUID clientId, Pageable pageable);
}
