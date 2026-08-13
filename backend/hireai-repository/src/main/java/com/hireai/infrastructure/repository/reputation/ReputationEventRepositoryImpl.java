package com.hireai.infrastructure.repository.reputation;

import com.hireai.domain.biz.reputation.enums.ReputationEventType;
import com.hireai.domain.biz.reputation.info.ReputationAggregates;
import com.hireai.domain.biz.reputation.model.ReputationEventModel;
import com.hireai.domain.biz.reputation.repository.ReputationEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link ReputationEventRepository}. Append-only:
 * there is no update or delete path here, and the database refuses both anyway (V27 triggers).
 */
@Repository
public class ReputationEventRepositoryImpl implements ReputationEventRepository {

    private final ReputationEventJpaRepository jpa;

    public ReputationEventRepositoryImpl(ReputationEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ReputationEventModel append(ReputationEventModel event) {
        jpa.save(new ReputationEventDO(
                event.id(), event.agentId(), event.taskId(), event.eventType(),
                event.quality(), event.weight(), event.occurredAt()));
        return event;
    }

    @Override
    public List<ReputationEventModel> findRecentByAgentId(UUID agentId, int limit) {
        return jpa.findByAgentIdOrderByOccurredAtDesc(agentId, PageRequest.of(0, limit))
                .stream()
                .map(this::toModel)
                .toList();
    }

    @Override
    public ReputationAggregates replayAggregates(UUID agentId) {
        ReputationEventJpaRepository.AggregateRow row = jpa.replayAggregates(agentId);
        return new ReputationAggregates(
                row.getReliabilitySum(), row.getReliabilityCount(),
                row.getSatisfactionSum(), row.getSatisfactionCount());
    }

    @Override
    public boolean existsByTaskIdAndEventType(UUID taskId, ReputationEventType eventType) {
        return taskId != null && jpa.existsByTaskIdAndEventType(taskId, eventType);
    }

    private ReputationEventModel toModel(ReputationEventDO entity) {
        return ReputationEventModel.rehydrate(
                entity.getId(), entity.getAgentId(), entity.getTaskId(), entity.getEventType(),
                entity.getQuality(), entity.getWeight(), entity.getOccurredAt());
    }
}
