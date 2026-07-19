package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyTaskJpaRepository extends JpaRepository<ApiKeyTaskDO, UUID> {
    @Query(value = "SELECT api_key_id FROM api_key_task WHERE task_id = :taskId LIMIT 1", nativeQuery = true)
    Optional<UUID> findApiKeyIdByTask(@Param("taskId") UUID taskId);
}
