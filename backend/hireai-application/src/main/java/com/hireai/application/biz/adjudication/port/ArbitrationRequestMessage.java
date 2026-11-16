package com.hireai.application.biz.adjudication.port;

import java.util.UUID;

/**
 * The arbitration request published to the worker (Java → queue → Python). Carries the task's frozen
 * {@code contract} (JSON, the single binding contract of Hard Invariant #4, #110) with its wire
 * description ({@code format}, {@code schema}) and the Client's prose {@code acceptanceCriteria} — the
 * subjective judgement no automated check makes — the task description (what the client actually asked for, so the arbitrator can judge
 * whether the output addresses THIS task and not merely produce a well-formed answer), plus the
 * agent's result. No money/identity fields (Inv #3): the worker returns only a ruling.
 *
 * <p>{@code deliverable} is the result's frozen DeliverySnapshot (D24), null when there is none.
 * When it is present the worker judges it and never fetches {@code resultUrl}, which is kept only
 * as a record of where the file came from.
 */
public record ArbitrationRequestMessage(
        UUID disputeId, UUID taskId, String correlationId,
        String format, String schema, String acceptanceCriteria, String taskDescription,
        String resultPayloadJson, String resultUrl, String reasonCategory,
        ArbitrationDeliverable deliverable, String contract) {

    /** Without the Contract (DLQ fixtures and older callers). */
    public ArbitrationRequestMessage(UUID disputeId, UUID taskId, String correlationId,
                                     String format, String schema, String acceptanceCriteria, String taskDescription,
                                     String resultPayloadJson, String resultUrl, String reasonCategory,
                                     ArbitrationDeliverable deliverable) {
        this(disputeId, taskId, correlationId, format, schema, acceptanceCriteria, taskDescription,
                resultPayloadJson, resultUrl, reasonCategory, deliverable, null);
    }
}
