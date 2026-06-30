package com.hireai.controller.biz.adjudication.dto;

import java.util.List;
import java.util.UUID;

public record DisputeOutcomeDTO(UUID taskId, String status, String effectiveCategory,
                                List<RulingDTO> rulings) {}
