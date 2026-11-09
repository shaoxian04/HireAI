package com.hireai.controller.biz.task.dto;

import java.util.List;

/**
 * Match-preview payload: an in-budget shortlist and an above-budget near-miss list. {@code noCloseMatch}
 * is true when the shortlist is empty only because every in-budget Agent's profile is below the
 * relevance floor for this task text (the web shows "no agent matches this task closely").
 */
public record MatchPreviewDTO(List<AgentOptionDTO> shortlist, List<AgentOptionDTO> nearMisses,
                              boolean noCloseMatch) {
}
