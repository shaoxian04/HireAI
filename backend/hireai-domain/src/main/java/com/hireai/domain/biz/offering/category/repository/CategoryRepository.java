package com.hireai.domain.biz.offering.category.repository;

import com.hireai.domain.biz.offering.category.model.CategoryList;

/** Read access to the Flyway-owned {@code categories} table. There is no write path. */
public interface CategoryRepository {

    /** The whole list in display order. */
    CategoryList load();
}
