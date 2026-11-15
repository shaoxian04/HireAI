package com.hireai.application.biz.acceptance.receipt;

import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;

import java.util.List;
import java.util.UUID;

/**
 * Signed receipts (spec S11, D40): one per settled Item, issued after its settlement commits and
 * readable by the Task's Client and the assigned Agent's Builder. A receipt gates nothing: issuing
 * one never blocks or changes a settlement, and nothing reads one back to decide anything.
 */
public interface ReceiptAppService {

    /**
     * The task's receipts, the whole-task one first then by Item number, issuing any a settled Item
     * still lacks. Empty while nothing has settled. NOT_FOUND unless {@code userId} is the task's
     * Client or the assigned Agent's Builder.
     */
    List<ReceiptModel> receiptsForTask(UUID taskId, UUID userId);

    /** One receipt, to the same two parties; NOT_FOUND for anyone else (existence is not leaked). */
    ReceiptModel receipt(UUID receiptId, UUID userId);

    /** Issues receipts for up to {@code limit} settled subjects that lack one (the sweeper's pass). */
    int issuePending(int limit);

    /** The public keys verifiers need: the active signing key first, then retired ones. */
    List<ReceiptSigner.PublicKey> publishedKeys();
}
