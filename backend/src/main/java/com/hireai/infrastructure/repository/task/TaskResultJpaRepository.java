package com.hireai.infrastructure.repository.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for task_results rows. Internal to infrastructure. */
public interface TaskResultJpaRepository extends JpaRepository<TaskResultJpaEntity, UUID> {

    Optional<TaskResultJpaEntity> findByTaskId(UUID taskId);
}
