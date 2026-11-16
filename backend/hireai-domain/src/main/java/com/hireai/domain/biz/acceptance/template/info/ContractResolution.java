package com.hireai.domain.biz.acceptance.template.info;

import com.hireai.domain.biz.acceptance.coverage.info.ContractTerms;

/**
 * A new Contract after version resolution (spec D36): the Template version it is made under, and
 * {@code fallback} when that is the previous version rather than the current one (null otherwise).
 */
public record ContractResolution(ContractTerms terms, VersionFallback fallback) {
}
