package com.hireai.controller.biz.agent.converter;

import com.hireai.controller.biz.agent.dto.AgentDTO;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.AgentVersionModel;
import com.hireai.domain.biz.task.model.OutputSpec;

/**
 * Explicit, hand-written converter from the Agent domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 */
public final class AgentModel2DTOConverter {

    private AgentModel2DTOConverter() {
    }

    public static AgentDTO toDTO(AgentModel agent) {
        AgentVersionModel version = agent.currentVersion();
        OutputSpec spec = version.outputSpec();
        AgentDTO.AgentVersionDTO versionDTO = new AgentDTO.AgentVersionDTO(
                version.id(),
                version.versionNumber(),
                new AgentDTO.OutputSpecDTO(spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                version.capabilityCategories(),
                version.webhookUrl(),
                version.maxExecutionSeconds(),
                version.pricing().price(),
                version.status().name());
        return new AgentDTO(
                agent.id(),
                agent.ownerId(),
                agent.name(),
                agent.status().name(),
                agent.currentVersionId(),
                agent.reputationScore(),
                versionDTO,
                agent.createdAt());
    }
}
