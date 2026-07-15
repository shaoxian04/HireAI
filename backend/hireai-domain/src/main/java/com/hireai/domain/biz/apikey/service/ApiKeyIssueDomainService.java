package com.hireai.domain.biz.apikey.service;

import com.hireai.domain.biz.apikey.model.IssuedApiKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Mints a new API key: generates a high-entropy raw key, hashes it, and builds the ACTIVE model. */
public interface ApiKeyIssueDomainService {
    IssuedApiKey issue(UUID userId, String name, BigDecimal spendCap, BigDecimal dailySpendCap, Instant now);
}
