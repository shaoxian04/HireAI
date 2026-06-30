package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisputeJpaRepository extends JpaRepository<DisputeDO, UUID> {
    Optional<DisputeDO> findByTaskId(UUID taskId);
}
