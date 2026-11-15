package com.hireai.domain.biz.ledger.settlement.service;

import com.hireai.domain.biz.adjudication.enums.DisputeParty;
import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.wallet.model.WalletModel;
import com.hireai.domain.shared.model.Money;

import java.util.Set;
import java.util.UUID;

/**
 * Charges a dispute fee to the side(s) the effective ruling goes against (loser pays, D43). Runs
 * after the settlement in the same unit of work, so a Client's share comes out of the refund just
 * credited and a Builder's out of its earnings wallet. Deterministic; never consults the LLM (Inv #3).
 */
public interface DisputeFeeDomainService {

    DisputeFeeCharge charge(DisputeFeeType type, Money fullCost, Money itemPrice, Set<DisputeParty> payers,
                            WalletModel clientWallet, WalletModel builderWallet, UUID taskId, String correlationId);
}
