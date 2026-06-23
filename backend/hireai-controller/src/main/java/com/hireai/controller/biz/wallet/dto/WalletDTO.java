package com.hireai.controller.biz.wallet.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Outbound HTTP DTO for a wallet. No domain types leak across the boundary. */
public record WalletDTO(
        UUID walletId,
        BigDecimal availableBalance,
        BigDecimal escrowBalance
) {
}
