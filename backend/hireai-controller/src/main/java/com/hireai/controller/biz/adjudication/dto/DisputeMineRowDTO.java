package com.hireai.controller.biz.adjudication.dto;

import java.time.Instant;
import java.util.UUID;

public record DisputeMineRowDTO(UUID disputeId, UUID taskId, String taskTitle, String status,
                                String proposedCategory, Instant updatedAt) {}
