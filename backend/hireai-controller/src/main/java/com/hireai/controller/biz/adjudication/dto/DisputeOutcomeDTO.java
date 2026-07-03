package com.hireai.controller.biz.adjudication.dto;

import java.util.List;
import java.util.UUID;

public record DisputeOutcomeDTO(UUID disputeId, UUID taskId, String status, String reasonCategory,
                                String effectiveCategory, List<RulingDTO> rulings) {}
