package com.hireai.infrastructure.repository.task.idempotency;

import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyTaskRepositoryImpl implements ApiKeyTaskRepository {

    private final ApiKeyTaskJpaRepository jpa;

    public ApiKeyTaskRepositoryImpl(ApiKeyTaskJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void attribute(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant now) {
        jpa.save(new ApiKeyTaskDO(taskId, apiKeyId, budget, now));
    }

    @Override
    public Optional<UUID> findApiKeyIdByTask(UUID taskId) {
        return jpa.findApiKeyIdByTask(taskId);
    }
}
