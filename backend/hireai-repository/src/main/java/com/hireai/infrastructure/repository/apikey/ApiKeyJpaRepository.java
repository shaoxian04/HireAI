package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyDO, UUID> {

    Optional<ApiKeyDO> findByKeyHashAndStatus(String keyHash, String status);

    List<ApiKeyDO> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("update ApiKeyDO k set k.lastUsedAt = :now where k.id = :id")
    void touchLastUsed(@Param("id") UUID id, @Param("now") Instant now);
}
