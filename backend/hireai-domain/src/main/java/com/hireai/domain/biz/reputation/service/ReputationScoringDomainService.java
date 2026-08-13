package com.hireai.domain.biz.reputation.service;

import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.info.ReputationScore;

/**
 * Derives an agent's reputation from its running aggregates as two independent shrinkage
 * estimators, blended (ADR 0003).
 *
 * <p>Pure arithmetic, framework-free, no persistence. Per Invariant #3's spirit the application
 * layer orchestrates, persists and enforces ownership only — the number itself is computed here.
 */
public interface ReputationScoringDomainService {

    ReputationScore score(ReputationAggregates aggregates);
}
