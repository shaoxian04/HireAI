package com.hireai.domain.biz.acceptance.receipt.repository;

import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Append-only store of receipts; at most one per settled subject (task + Item number). */
public interface ReceiptRepository {

    /** Stores {@code receipt} unless its subject already has one; true when this call stored it. */
    boolean insertIfAbsent(ReceiptModel receipt);

    Optional<ReceiptModel> findById(UUID receiptId);

    /** The task's receipts: the whole-task one first, then by Item number. */
    List<ReceiptModel> findByTaskId(UUID taskId);
}
