package com.hireai.controller.biz.task.dto;

import java.util.List;

/** Match-preview payload: an in-budget shortlist and an above-budget near-miss list. */
public record MatchPreviewDTO(List<AgentOptionDTO> shortlist, List<AgentOptionDTO> nearMisses) {
}
