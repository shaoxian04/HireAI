package com.hireai.infrastructure.repository.agent;

import com.hireai.domain.biz.agent.enums.AgentStatus;
import com.hireai.domain.biz.agent.info.AgentCandidate;
import com.hireai.domain.biz.agent.model.AgentModel;
import com.hireai.domain.biz.agent.model.AgentVersionModel;
import com.hireai.domain.biz.agent.model.Pricing;
import com.hireai.domain.biz.agent.repository.AgentQuery;
import com.hireai.domain.biz.agent.repository.AgentRepository;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.shared.exception.DomainException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link AgentRepository}. Maps
 * {@code AgentModel} &lt;-&gt; JPA entities, persisting the version child only through the
 * root, and serialises the output spec via {@link OutputSpecJsonMapper}. The candidate read
 * maps the native projection to the shared {@link AgentCandidate} contract record.
 */
@Repository
public class AgentRepositoryImpl implements AgentRepository {

    private final AgentJpaRepository agentJpa;
    private final AgentVersionJpaRepository versionJpa;
    private final OutputSpecJsonMapper outputSpecJsonMapper;

    public AgentRepositoryImpl(AgentJpaRepository agentJpa, AgentVersionJpaRepository versionJpa,
                               OutputSpecJsonMapper outputSpecJsonMapper) {
        this.agentJpa = agentJpa;
        this.versionJpa = versionJpa;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
    }

    @Override
    public AgentModel save(AgentModel agent) {
        agentJpa.save(new AgentJpaEntity(
                agent.id(), agent.ownerId(), agent.name(), agent.status().name(),
                agent.currentVersionId(), agent.reputationScore(), agent.createdAt()));

        AgentVersionModel version = agent.currentVersion();
        if (version != null && versionJpa.findById(version.id()).isEmpty()) {
            versionJpa.save(new AgentVersionJpaEntity(
                    version.id(), version.agentId(), version.versionNumber(),
                    outputSpecJsonMapper.toJson(version.outputSpec()),
                    version.capabilityCategories(), version.webhookUrl(),
                    version.maxExecutionSeconds(), version.pricing().price(), version.createdAt()));
        }
        return agent;
    }

    @Override
    public Optional<AgentModel> findById(UUID agentId) {
        return agentJpa.findById(agentId).map(this::toModel);
    }

    @Override
    public List<AgentModel> findByOwnerId(UUID ownerId, AgentQuery query) {
        return agentJpa.findByOwnerIdOrderByGmtCreateDesc(
                        ownerId, PageRequest.of(query.page(), query.size()))
                .stream()
                .map(this::toModel)
                .toList();
    }

    @Override
    public List<AgentCandidate> findActiveCandidates(String category, BigDecimal maxPrice) {
        String normalisedCategory = category == null ? "" : category.trim().toLowerCase();
        return versionJpa.findActiveCandidates(normalisedCategory, maxPrice).stream()
                .map(this::rowToCandidate)
                .toList();
    }

    @Override
    public java.util.Optional<AgentCandidate> findCandidateByVersionId(UUID agentVersionId) {
        return versionJpa.findCandidateByVersionId(agentVersionId)
                .map(this::rowToCandidate);
    }

    private AgentCandidate rowToCandidate(AgentVersionJpaRepository.AgentCandidateRow row) {
        return new AgentCandidate(
                row.getAgentId(), row.getAgentVersionId(),
                List.of(row.getCapabilityCategories()), row.getPrice(),
                row.getWebhookUrl(), row.getMaxExecutionSeconds(), row.getReputationScore(),
                row.getOutputSpec());
    }

    private AgentModel toModel(AgentJpaEntity entity) {
        AgentVersionModel version = versionJpa.findByAgentIdAndVersionNumber(entity.getId(), 1)
                .map(this::toVersionModel)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent " + entity.getId() + " has no version 1"));
        return new AgentModel(
                entity.getId(), entity.getOwnerId(), entity.getName(),
                AgentStatus.valueOf(entity.getStatus()), entity.getCurrentVersionId(),
                entity.getReputationScore(), version, entity.getGmtCreate());
    }

    private AgentVersionModel toVersionModel(AgentVersionJpaEntity entity) {
        return new AgentVersionModel(
                entity.getId(), entity.getAgentId(), entity.getVersionNumber(),
                outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCapabilityCategories(), entity.getWebhookUrl(),
                entity.getMaxExecutionSeconds(), Pricing.of(entity.getPrice()), entity.getGmtCreate());
    }
}
