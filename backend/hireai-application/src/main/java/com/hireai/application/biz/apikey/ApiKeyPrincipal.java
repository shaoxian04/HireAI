package com.hireai.application.biz.apikey;

import java.math.BigDecimal;
import java.util.UUID;

/** Resolved identity of an API-key request. Caps are nullable (uncapped). */
public record ApiKeyPrincipal(UUID userId, UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap) {}
