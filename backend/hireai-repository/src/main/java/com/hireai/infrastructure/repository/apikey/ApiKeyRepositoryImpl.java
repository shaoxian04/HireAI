package com.hireai.infrastructure.repository.apikey;

import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.ApiKeyStatus;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepositoryImpl implements ApiKeyRepository {

    private final ApiKeyJpaRepository jpa;

    public ApiKeyRepositoryImpl(ApiKeyJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ApiKeyModel save(ApiKeyModel k) {
        jpa.save(new ApiKeyDO(k.id(), k.userId(), k.keyHash(), k.displayPrefix(), k.name(),
                k.spendCap(), k.dailySpendCap(), k.status().name(), k.lastUsedAt(),
                k.createdAt(), k.revokedAt()));
        return k;
    }

    @Override
    public Optional<ApiKeyModel> findActiveByHash(String keyHash) {
        return jpa.findByKeyHashAndStatus(keyHash, ApiKeyStatus.ACTIVE.name()).map(this::toModel);
    }

    @Override
    public Optional<ApiKeyModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    @Override
    public List<ApiKeyModel> findByUserId(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toModel).toList();
    }

    @Override
    @Transactional
    public void touchLastUsed(UUID id, Instant now) {
        jpa.touchLastUsed(id, now);
    }

    private ApiKeyModel toModel(ApiKeyDO d) {
        return ApiKeyModel.rehydrate(d.getId(), d.getUserId(), d.getKeyHash(), d.getDisplayPrefix(),
                d.getName(), d.getSpendCap(), d.getDailySpendCap(),
                ApiKeyStatus.valueOf(d.getStatus()), d.getLastUsedAt(), d.getCreatedAt(),
                d.getRevokedAt());
    }
}
