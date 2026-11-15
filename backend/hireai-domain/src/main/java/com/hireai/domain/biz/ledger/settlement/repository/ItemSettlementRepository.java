package com.hireai.domain.biz.ledger.settlement.repository;

import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;

import java.util.List;
import java.util.UUID;

/** Append-only store of Item settlements (one per Item; UNIQUE item_id is the backstop). */
public interface ItemSettlementRepository {

    void append(ItemSettlementModel settlement);

    List<ItemSettlementModel> findByTaskId(UUID taskId);
}
