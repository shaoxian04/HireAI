package com.hireai.infrastructure.repository.offering.agent;

import com.hireai.domain.biz.offering.agent.enums.AgentStatus;
import com.hireai.domain.biz.offering.agent.enums.AgentVersionStatus;
import com.hireai.domain.biz.offering.agent.info.AgentCandidate;
import com.hireai.domain.biz.offering.agent.model.AgentModel;
import com.hireai.domain.biz.offering.agent.model.AgentVersionModel;
import com.hireai.domain.biz.offering.agent.model.Pricing;
import com.hireai.domain.biz.offering.agent.repository.AgentQuery;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;
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
        agentJpa.save(new AgentDO(
                agent.id(), agent.ownerId(), agent.name(), agent.status().name(),
                agent.currentVersionId(), agent.reputationScore(), agent.createdAt()));

        AgentVersionModel version = agent.currentVersion();
        if (version != null && versionJpa.findById(version.id()).isEmpty()) {
            versionJpa.save(new AgentVersionDO(
                    version.id(), version.agentId(), version.versionNumber(),
                    outputSpecJsonMapper.toJson(version.outputSpec()),
                    version.capabilityCategories(), version.webhookUrl(),
                    version.maxExecutionSeconds(), version.pricing().price(),
                    version.status().name(), version.createdAt()));
        }
        return agent;
    }

    @Override
    public void publishNewVersion(AgentModel agent) {
        AgentVersionModel v = agent.currentVersion();
        // 1. demote the prior ACTIVE version (direct SQL — runs before the insert).
        versionJpa.updateStatus(agent.id(),
                AgentVersionStatus.ACTIVE.name(), AgentVersionStatus.DEPRECATED.name());
        // 2. insert the new ACTIVE version (the prior one is retained as DEPRECATED history).
        versionJpa.save(new AgentVersionDO(
                v.id(), v.agentId(), v.versionNumber(),
                outputSpecJsonMapper.toJson(v.outputSpec()),
                v.capabilityCategories(), v.webhookUrl(),
                v.maxExecutionSeconds(), v.pricing().price(),
                v.status().name(), v.createdAt()));
        // 3. update the agent row (current_version_id now points at the new version when ACTIVE).
        agentJpa.save(new AgentDO(
                agent.id(), agent.ownerId(), agent.name(), agent.status().name(),
                agent.currentVersionId(), agent.reputationScore(), agent.createdAt()));
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

    @Override
    public Optional<UUID> findOwnerByVersionId(UUID agentVersionId) {
        return versionJpa.findOwnerByVersionId(agentVersionId);
    }

    private AgentCandidate rowToCandidate(AgentVersionJpaRepository.AgentCandidateRow row) {
        return new AgentCandidate(
                row.getAgentId(), row.getAgentVersionId(),
                List.of(row.getCapabilityCategories()), row.getPrice(),
                row.getWebhookUrl(), row.getMaxExecutionSeconds(), row.getReputationScore(),
                row.getOutputSpec(),
                row.getMaxConcurrent() == null ? 5 : row.getMaxConcurrent(),
                row.getInFlight() == null ? 0L : row.getInFlight(),
                row.getSampleCount() == null ? 0L : row.getSampleCount());
    }

    private AgentModel toModel(AgentDO entity) {
        AgentVersionModel version = versionJpa
                .findByAgentIdAndStatus(entity.getId(), AgentVersionStatus.ACTIVE.name())
                .map(this::toVersionModel)
                .orElseThrow(() -> new DomainException(ResultCode.INTERNAL_ERROR,
                        "Agent " + entity.getId() + " has no ACTIVE version"));
        return new AgentModel(
                entity.getId(), entity.getOwnerId(), entity.getName(),
                AgentStatus.valueOf(entity.getStatus()), entity.getCurrentVersionId(),
                entity.getReputationScore(), version, entity.getGmtCreate());
    }

    private AgentVersionModel toVersionModel(AgentVersionDO entity) {
        return new AgentVersionModel(
                entity.getId(), entity.getAgentId(), entity.getVersionNumber(),
                outputSpecJsonMapper.fromJson(entity.getOutputSpec()),
                entity.getCapabilityCategories(), entity.getWebhookUrl(),
                entity.getMaxExecutionSeconds(), Pricing.of(entity.getPrice()),
                AgentVersionStatus.valueOf(entity.getStatus()), entity.getGmtCreate());
    }
}
