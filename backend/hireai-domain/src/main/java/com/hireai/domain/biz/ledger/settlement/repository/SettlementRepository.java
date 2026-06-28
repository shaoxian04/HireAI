package com.hireai.domain.biz.ledger.settlement.repository;

import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;

import java.util.Optional;
import java.util.UUID;

/** Persistence contract for the Settlement aggregate (one settlement per task). */
public interface SettlementRepository {

    SettlementModel save(SettlementModel settlement);

    Optional<SettlementModel> findByTaskId(UUID taskId);
}
