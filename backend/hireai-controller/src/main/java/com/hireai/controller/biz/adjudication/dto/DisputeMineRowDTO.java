package com.hireai.controller.biz.adjudication.dto;

import java.time.Instant;
import java.util.UUID;

/** {@code awaitingYou}: the proposed ruling goes against the viewer's side, which has not acted on it yet. */
public record DisputeMineRowDTO(UUID disputeId, UUID taskId, String taskTitle, String status,
                                String proposedCategory, Instant updatedAt, boolean awaitingYou) {}
