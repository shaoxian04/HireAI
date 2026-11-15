package com.hireai.controller.biz.task.converter;

import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.controller.biz.task.dto.AgentOptionDTO;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;
import com.hireai.domain.biz.task.routing.info.NoMatchExplanation;

/** Maps the app-layer MatchPreview read model to the HTTP DTO (availability boolean -> label). */
public final class MatchPreview2DTOConverter {

    private MatchPreview2DTOConverter() {
    }

    public static MatchPreviewDTO toDTO(MatchPreview preview) {
        return new MatchPreviewDTO(
                preview.shortlist().stream().map(MatchPreview2DTOConverter::option).toList(),
                preview.nearMisses().stream().map(MatchPreview2DTOConverter::option).toList(),
                preview.noCloseMatch(),
                noMatch(preview.noMatch()));
    }

    private static MatchPreviewDTO.NoMatchDTO noMatch(NoMatchExplanation e) {
        if (e == null) {
            return null;
        }
        return new MatchPreviewDTO.NoMatchDTO(e.summary(),
                e.exclusions().stream()
                        .map(x -> new MatchPreviewDTO.ExclusionDTO(x.blocker().name(), x.parameter(), x.agents()))
                        .toList(),
                e.suggestions().stream()
                        .map(x -> new MatchPreviewDTO.SuggestionDTO(x.parameter(), x.agents()))
                        .toList());
    }

    private static AgentOptionDTO option(AgentOption o) {
        return new AgentOptionDTO(o.agentId(), o.agentVersionId(), o.agentName(), o.tagline(),
                o.logoUrl(), o.price(), o.reputationScore(),
                o.available() ? "AVAILABLE" : "BUSY", o.outputFormat(), o.capabilityCategories());
    }
}
