package com.hireai.controller.biz.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Match-preview payload: an in-budget shortlist and an above-budget near-miss list. {@code noCloseMatch}
 * is true when the shortlist is empty only because every in-budget Agent's profile is below the
 * relevance floor for this task text (the web shows "no agent matches this task closely").
 *
 * <p>{@code noMatch} (#91, spec D35) explains an empty shortlist, and is omitted otherwise: each blocker
 * ({@code PARAMETER} with its name, {@code RELEVANCE_FLOOR}, or {@code TEMPLATE}) with the number of
 * in-budget Agents it excluded, and for each Contract parameter how many Agents leaving it unset would
 * make eligible. The platform never relaxes a Contract itself.
 */
public record MatchPreviewDTO(List<AgentOptionDTO> shortlist, List<AgentOptionDTO> nearMisses,
                              boolean noCloseMatch,
                              @JsonInclude(JsonInclude.Include.NON_NULL) NoMatchDTO noMatch) {

    public record NoMatchDTO(String summary, List<ExclusionDTO> exclusions, List<SuggestionDTO> suggestions) {
    }

    public record ExclusionDTO(String blocker, String parameter, int agents) {
    }

    public record SuggestionDTO(String parameter, int agents) {
    }
}
