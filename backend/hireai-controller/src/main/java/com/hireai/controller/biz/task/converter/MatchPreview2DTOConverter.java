package com.hireai.controller.biz.task.converter;

import com.hireai.application.biz.task.MatchPreviewAppService.AgentOption;
import com.hireai.application.biz.task.MatchPreviewAppService.MatchPreview;
import com.hireai.controller.biz.task.dto.AgentOptionDTO;
import com.hireai.controller.biz.task.dto.MatchPreviewDTO;

/** Maps the app-layer MatchPreview read model to the HTTP DTO (availability boolean -> label). */
public final class MatchPreview2DTOConverter {

    private MatchPreview2DTOConverter() {
    }

    public static MatchPreviewDTO toDTO(MatchPreview preview) {
        return new MatchPreviewDTO(
                preview.shortlist().stream().map(MatchPreview2DTOConverter::option).toList(),
                preview.nearMisses().stream().map(MatchPreview2DTOConverter::option).toList());
    }

    private static AgentOptionDTO option(AgentOption o) {
        return new AgentOptionDTO(o.agentId(), o.agentVersionId(), o.agentName(), o.tagline(),
                o.logoUrl(), o.price(), o.reputationScore(),
                o.available() ? "AVAILABLE" : "BUSY", o.outputFormat(), o.capabilityCategories());
    }
}
