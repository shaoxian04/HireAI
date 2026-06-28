package com.hireai.domain.biz.wallet.service;

import com.hireai.domain.biz.wallet.info.SettlementBreakdown;
import com.hireai.domain.biz.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

import java.util.UUID;

/**
 * Deterministic escrow settlement (Invariant #3: the LLM may produce rulings, but money
 * movement is pure domain arithmetic). Mutates the wallet aggregates, which append the
 * ledger entries; persistence is the caller's job. Framework-free; the bean is registered
 * in DomainServiceConfig.
 */
public interface SettlementDomainService {

    /**
     * Client accepted: release net (85%) + commission (15%) out of the client's escrow and
     * credit the net to the builder's available balance. {@code clientWallet} and
     * {@code builderWallet} MAY be the same instance (a client accepting their own agent's work).
     *
     * <p>{@code budget} MUST be the task's frozen escrow amount of record ({@code task.budget()});
     * passing anything else either over-releases (rejected by the aggregate) or silently strands
     * residual escrow.
     */
    SettlementBreakdown settleAcceptance(WalletModel clientWallet, WalletModel builderWallet,
                                         Money budget, UUID taskId, String correlationId);

    /**
     * Client rejected: return the full budget from escrow to the client's available balance.
     *
     * <p>{@code budget} MUST be the task's frozen escrow amount of record ({@code task.budget()});
     * passing a smaller value silently strands the residual escrow with no compensating entry.
     */
    void settleRejection(WalletModel clientWallet, Money budget, UUID taskId, String correlationId);
}
