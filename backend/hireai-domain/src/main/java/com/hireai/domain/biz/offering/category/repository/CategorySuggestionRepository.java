package com.hireai.domain.biz.offering.category.repository;

import com.hireai.domain.biz.offering.category.model.CategorySuggestion;

import java.util.List;
import java.util.UUID;

/** Stores Builders' category suggestions for admins. */
public interface CategorySuggestionRepository {

    void record(CategorySuggestion suggestion);

    List<CategorySuggestion> findByAgentId(UUID agentId);
}
