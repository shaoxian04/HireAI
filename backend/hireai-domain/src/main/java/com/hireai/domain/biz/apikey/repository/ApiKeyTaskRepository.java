package com.hireai.domain.biz.apikey.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Records which API key submitted which task, with its budget (attribution + spend reads). */
public interface ApiKeyTaskRepository {
    void attribute(UUID taskId, UUID apiKeyId, BigDecimal budget, Instant now);
}
