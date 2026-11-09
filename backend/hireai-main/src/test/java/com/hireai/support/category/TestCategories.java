package com.hireai.support.category;

import com.hireai.domain.biz.offering.category.model.Category;
import com.hireai.domain.biz.offering.category.model.CategoryList;
import com.hireai.domain.biz.offering.category.model.CategorySuggestion;
import com.hireai.domain.biz.offering.category.repository.CategoryRepository;
import com.hireai.domain.biz.offering.category.repository.CategorySuggestionRepository;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory category list and suggestion store for unit tests that construct app services directly. */
public final class TestCategories {

    private TestCategories() {
    }

    /** A category repository holding exactly {@code slugs} (labels = slugs). */
    public static CategoryRepository listOf(String... slugs) {
        CategoryList list = new CategoryList(Arrays.stream(slugs).map(s -> new Category(s, s)).toList());
        return () -> list;
    }

    /** Records suggestions in memory. */
    public static final class InMemorySuggestions implements CategorySuggestionRepository {
        public final List<CategorySuggestion> recorded = new CopyOnWriteArrayList<>();

        @Override
        public void record(CategorySuggestion suggestion) {
            recorded.add(suggestion);
        }

        @Override
        public List<CategorySuggestion> findByAgentId(UUID agentId) {
            return recorded.stream().filter(s -> s.agentId().equals(agentId)).toList();
        }
    }
}
