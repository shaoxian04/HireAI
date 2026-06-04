package com.hireai.controller.biz.wallet.converter;

import com.hireai.controller.biz.wallet.dto.LedgerEntryDTO;
import com.hireai.controller.biz.wallet.dto.WalletDTO;
import com.hireai.domain.biz.wallet.model.LedgerEntryModel;
import com.hireai.domain.biz.wallet.model.WalletModel;

/**
 * Explicit, hand-written converter from domain models to outbound DTOs.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 */
public final class WalletModel2DTOConverter {

    private WalletModel2DTOConverter() {
    }

    public static WalletDTO toDTO(WalletModel wallet) {
        return new WalletDTO(
                wallet.id(),
                wallet.available().value(),
                wallet.escrow().value());
    }

    public static LedgerEntryDTO toDTO(LedgerEntryModel entry) {
        return new LedgerEntryDTO(
                entry.id(),
                entry.type().name(),
                entry.amount().value(),
                entry.balanceAfter().value(),
                entry.relatedTaskId(),
                entry.createdAt());
    }
}
