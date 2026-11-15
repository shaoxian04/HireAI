package com.hireai.domain.biz.ledger.settlement.repository;

import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;

import java.util.List;
import java.util.UUID;

/** Append-only record of how each dispute fee was borne (one row per dispute and fee type). */
public interface DisputeFeeRepository {

    void append(UUID disputeId, UUID taskId, DisputeFeeCharge charge);

    /** The dispute's fee records, arbitration cost first. */
    List<DisputeFeeCharge> findByDisputeId(UUID disputeId);
}
