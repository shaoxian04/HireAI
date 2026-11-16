package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-side facts a receipt cites that no aggregate exposes: what still needs one, and the ledger rows. */
public interface ReceiptEvidenceQueryPort {

    /**
     * Settled subjects with no receipt yet, oldest settlement first: a Task settlement (itemNo null)
     * or an Item settlement of a multi-Item Task.
     */
    List<SettledSubject> settledWithoutReceipt(int limit);

    /** The ledger entries carrying {@code correlationId} for the task, in the order they were written. */
    List<LedgerLine> ledgerEntries(UUID taskId, String correlationId);

    record SettledSubject(UUID taskId, Integer itemNo) {
    }

    record LedgerLine(UUID id, UUID walletOwnerId, String type, BigDecimal amount, Instant createdAt) {
    }
}
