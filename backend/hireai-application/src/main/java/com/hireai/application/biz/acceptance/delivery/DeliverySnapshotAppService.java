package com.hireai.application.biz.acceptance.delivery;

import com.hireai.application.biz.adjudication.port.ArbitrationDeliverable;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.task.model.TaskResultModel;

import java.util.Optional;

/**
 * The DeliverySnapshot use cases (D24). {@link #capture} runs once per result, from the validation
 * gate, before any check; everything after that (checks, the Client's view and download,
 * arbitration) reads the snapshot through {@link #findForResult} / {@link #content}, never the link.
 */
public interface DeliverySnapshotAppService {

    /**
     * Fetches the result's link through the SSRF-guarded fetch port, within the Contract's byte limit
     * (capped by {@code hireai.delivery.max-bytes}), hashes and stores the bytes and records the
     * snapshot. Idempotent per result: a result that already has a snapshot returns it unfetched.
     */
    default DeliveryOutcome capture(Contract contract, TaskResultModel result) {
        return capture(contract, result, null);
    }

    /** {@link #capture(Contract, TaskResultModel)} for one Item's attempt ({@code itemId} null for a whole Task). */
    DeliveryOutcome capture(Contract contract, TaskResultModel result, java.util.UUID itemId);

    Optional<DeliverySnapshot> findForResult(TaskResultModel result);

    /** The frozen bytes of {@code snapshot}; empty once retention has retired them. */
    Optional<byte[]> content(DeliverySnapshot snapshot);

    /**
     * The result's snapshot as the arbitration worker receives it, bytes inline (base64) up to
     * {@code hireai.arbitration.inline-deliverable-max-bytes}; empty when the result has no snapshot.
     */
    Optional<ArbitrationDeliverable> forArbitration(TaskResultModel result);
}
