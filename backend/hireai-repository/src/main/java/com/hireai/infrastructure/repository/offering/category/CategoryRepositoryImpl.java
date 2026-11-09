package com.hireai.infrastructure.repository.offering.category;

import com.hireai.domain.biz.offering.category.model.Category;
import com.hireai.domain.biz.offering.category.model.CategoryList;
import com.hireai.domain.biz.offering.category.repository.CategoryRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/**
 * Reads the Flyway-owned {@code categories} table. Not cached: the list is a dozen rows and a
 * migration may change it, so every validation sees the current table.
 */
@Repository
public class CategoryRepositoryImpl implements CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CategoryRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CategoryList load() {
        return new CategoryList(jdbc.query(
                "SELECT slug, label FROM categories ORDER BY sort_order, slug", Map.of(),
                (rs, i) -> new Category(rs.getString("slug"), rs.getString("label"))));
    }
}
