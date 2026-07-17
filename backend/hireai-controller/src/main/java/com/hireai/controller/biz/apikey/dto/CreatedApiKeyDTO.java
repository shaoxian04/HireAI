package com.hireai.controller.biz.apikey.dto;

import java.math.BigDecimal;

/** The create response — the ONLY place the raw key is ever returned. */
public record CreatedApiKeyDTO(String id, String name, String displayPrefix,
                               BigDecimal spendCap, BigDecimal dailySpendCap, String rawKey) {}
