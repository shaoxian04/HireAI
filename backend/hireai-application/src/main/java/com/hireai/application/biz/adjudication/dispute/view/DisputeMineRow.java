package com.hireai.application.biz.adjudication.dispute.view;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a user's own disputes list ({@code /client/disputes} or {@code /builder/disputes}).
 * {@code awaitingYou}: the proposed ruling goes against this user's side, which has neither accepted
 * nor appealed it yet.
 */
public record DisputeMineRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String proposedCategory, Instant updatedAt, boolean awaitingYou) {}
