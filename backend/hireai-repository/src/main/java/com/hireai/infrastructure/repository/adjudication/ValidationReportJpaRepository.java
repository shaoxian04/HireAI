package com.hireai.infrastructure.repository.adjudication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ValidationReportJpaRepository extends JpaRepository<ValidationReportDO, UUID> {
    Optional<ValidationReportDO> findByTaskIdAndAttemptNo(UUID taskId, int attemptNo);
}
