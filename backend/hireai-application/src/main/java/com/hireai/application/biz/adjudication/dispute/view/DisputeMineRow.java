package com.hireai.application.biz.adjudication.dispute.view;

import java.time.Instant;
import java.util.UUID;

/** One row of the client's own disputes list (`/client/disputes`). */
public record DisputeMineRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String proposedCategory, Instant updatedAt) {}
