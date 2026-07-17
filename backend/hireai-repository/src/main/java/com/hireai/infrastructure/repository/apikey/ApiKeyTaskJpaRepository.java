package com.hireai.infrastructure.repository.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApiKeyTaskJpaRepository extends JpaRepository<ApiKeyTaskDO, UUID> {}
