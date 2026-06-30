package com.hireai.application.biz.adjudication.port;

import java.util.UUID;

/**
 * The arbitration request published to the worker (Java → queue → Python). Carries the FULL output
 * spec — including acceptanceCriteria, the subjective judgement the validation gate deliberately
 * skipped — plus the agent's result. No money/identity fields (Inv #3): the worker returns only a ruling.
 */
public record ArbitrationRequestMessage(
        UUID disputeId, UUID taskId, String correlationId,
        String format, String schema, String acceptanceCriteria,
        String resultPayloadJson, String resultUrl, String reasonCategory) {}
