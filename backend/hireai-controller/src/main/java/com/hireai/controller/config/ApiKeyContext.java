package com.hireai.controller.config;

import java.math.BigDecimal;
import java.util.UUID;

/** Auth details for an API-key request: which key, and its (nullable) spend caps. */
public record ApiKeyContext(UUID keyId, BigDecimal spendCap, BigDecimal dailySpendCap) {}
