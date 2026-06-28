package com.hireai.controller.biz.wallet.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Outbound HTTP DTO for a single ledger entry. */
public record LedgerEntryDTO(
        UUID id,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID relatedTaskId,
        Instant createdAt
) {
}
