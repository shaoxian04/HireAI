package com.hireai.application.biz.catalogue.impl;

import com.hireai.application.biz.catalogue.CatalogueReadAppService;
import com.hireai.application.port.query.CatalogueQueryPort;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Implements the public catalogue read use cases. Bounds page size as defence in depth
 * (the DAO also clamps, but the app service enforces the contract at the application boundary).
 * No ownership scoping: any authenticated user can browse ACTIVE + listed agents (spec §6).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogueReadAppServiceImpl implements CatalogueReadAppService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int REVIEWS_LIMIT = 20;

    private final CatalogueQueryPort catalogueQueryPort;

    @Override
    public List<CatalogueQueryPort.AgentCardRow> search(String q, String category, String sort,
                                                        int page, int size) {
        if (sort != null && !sort.isBlank() && !CatalogueQueryPort.SORT_KEYS.contains(sort)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Unknown sort: " + sort);
        }
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return catalogueQueryPort.searchCards(q, category, sort, Math.max(page, 0), bounded);
    }

    @Override
    public CatalogueQueryPort.AgentProfileRow getProfile(UUID agentId) {
        return catalogueQueryPort.findProfile(agentId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Agent not found: " + agentId));
    }

    @Override
    public List<CatalogueQueryPort.CategoryCountRow> categories() {
        return catalogueQueryPort.categoryCounts();
    }

    @Override
    public List<CatalogueQueryPort.ReviewRow> reviews(UUID agentId) {
        return catalogueQueryPort.reviewsForAgent(agentId, REVIEWS_LIMIT);
    }
}
