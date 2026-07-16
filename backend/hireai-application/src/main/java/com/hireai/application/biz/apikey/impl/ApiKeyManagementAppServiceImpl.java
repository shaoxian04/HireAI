package com.hireai.application.biz.apikey.impl;

import com.hireai.application.biz.apikey.ApiKeyManagementAppService;
import com.hireai.domain.biz.apikey.model.ApiKeyModel;
import com.hireai.domain.biz.apikey.model.IssuedApiKey;
import com.hireai.domain.biz.apikey.repository.ApiKeyRepository;
import com.hireai.domain.biz.apikey.service.ApiKeyIssueDomainService;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ApiKeyManagementAppServiceImpl implements ApiKeyManagementAppService {

    private final ApiKeyIssueDomainService issueDomainService;
    private final ApiKeyRepository repository;
    private final Clock clock;

    public ApiKeyManagementAppServiceImpl(ApiKeyIssueDomainService issueDomainService,
                                          ApiKeyRepository repository, Clock clock) {
        this.issueDomainService = issueDomainService;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public IssuedApiKey create(UUID ownerId, String name, BigDecimal spendCap, BigDecimal dailySpendCap) {
        IssuedApiKey issued = issueDomainService.issue(ownerId, name, spendCap, dailySpendCap, clock.instant());
        repository.save(issued.model());
        log.info("API key {} created for user {}", issued.model().id(), ownerId);
        return issued;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyModel> list(UUID ownerId) {
        return repository.findByUserId(ownerId);
    }

    @Override
    @Transactional
    public ApiKeyModel revoke(UUID keyId, UUID ownerId) {
        ApiKeyModel key = repository.findById(keyId)
                .filter(k -> k.userId().equals(ownerId))   // owner-scoped: non-owner → NOT_FOUND (no leak)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "API key not found"));
        ApiKeyModel revoked = key.revoke(clock.instant());
        repository.save(revoked);
        log.info("API key {} revoked by user {}", keyId, ownerId);
        return revoked;
    }
}
