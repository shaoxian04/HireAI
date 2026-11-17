package com.hireai.controller.biz.receipt.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One receipt in a task's list: its signed body (parsed, for display) and signature. To verify,
 * download {@code GET /api/receipts/{id}}, which serves the exact signed bytes.
 */
public record ReceiptDTO(UUID id, UUID taskId, Integer itemNo, String keyId, Instant issuedAt,
                         JsonNode receipt, String signature) {
}
