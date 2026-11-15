package com.hireai.domain.biz.acceptance.template.repository;

import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;

import java.util.List;
import java.util.Optional;

/**
 * Read-only repository for the Template aggregate. Templates are written only by Flyway
 * ({@code contract_templates}), so there is no save.
 */
public interface TemplateRepository {

    Optional<TemplateModel> find(TemplateRef ref);

    /** Every published version, ordered by key then version. */
    List<TemplateModel> findAll();
}
