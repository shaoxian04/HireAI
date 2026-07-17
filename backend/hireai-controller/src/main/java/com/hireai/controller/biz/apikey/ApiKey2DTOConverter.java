package com.hireai.controller.biz.apikey;

import com.hireai.controller.biz.apikey.dto.ApiKeyDTO;
import com.hireai.controller.biz.apikey.dto.CreatedApiKeyDTO;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;

/** Maps API-key aggregates to their HTTP DTOs. */
public final class ApiKey2DTOConverter {
    private ApiKey2DTOConverter() {}

    public static ApiKeyDTO toDTO(ApiKeyModel k) {
        return new ApiKeyDTO(k.id().toString(), k.name(), k.displayPrefix(), k.spendCap(),
                k.dailySpendCap(), k.status().name(), k.lastUsedAt(), k.createdAt());
    }

    public static CreatedApiKeyDTO toCreatedDTO(IssuedApiKey issued) {
        ApiKeyModel k = issued.model();
        return new CreatedApiKeyDTO(k.id().toString(), k.name(), k.displayPrefix(),
                k.spendCap(), k.dailySpendCap(), issued.rawKey());
    }
}
