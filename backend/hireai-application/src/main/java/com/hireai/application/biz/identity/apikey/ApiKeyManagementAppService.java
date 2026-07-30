package com.hireai.application.biz.identity.apikey;

import com.hireai.domain.biz.identity.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.identity.apikey.model.IssuedApiKey;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** JWT-scoped key management. All ops are owner-scoped (Invariant #5). */
public interface ApiKeyManagementAppService {

    IssuedApiKey create(UUID ownerId, String name, BigDecimal spendCap, BigDecimal dailySpendCap);

    List<ApiKeyModel> list(UUID ownerId);

    ApiKeyModel revoke(UUID keyId, UUID ownerId);
}
