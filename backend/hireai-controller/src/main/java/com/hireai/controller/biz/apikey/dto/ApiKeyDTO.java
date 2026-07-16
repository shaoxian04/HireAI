package com.hireai.controller.biz.apikey.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** A key as shown in the management list. NEVER carries the raw key. */
public record ApiKeyDTO(String id, String name, String displayPrefix, BigDecimal spendCap,
                        BigDecimal dailySpendCap, String status, Instant lastUsedAt, Instant createdAt) {}
