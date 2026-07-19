package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
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
