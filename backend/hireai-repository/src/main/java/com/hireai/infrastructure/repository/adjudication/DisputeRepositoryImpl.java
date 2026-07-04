package com.hireai.infrastructure.repository.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DisputeRepositoryImpl implements DisputeRepository {

    private final DisputeJpaRepository jpa;
    private final DisputeRulingJpaRepository rulingJpa;

    public DisputeRepositoryImpl(DisputeJpaRepository jpa, DisputeRulingJpaRepository rulingJpa) {
        this.jpa = jpa;
        this.rulingJpa = rulingJpa;
    }

    @Override
    public DisputeModel save(DisputeModel d) {
        jpa.save(new DisputeDO(d.id(), d.taskId(), d.raisedBy(), d.reasonCategory().name(),
                d.status().name(), d.correlationId(), d.resolvedAt(), d.createdAt()));

        // Append-only: insert only the ruling rows not yet persisted (the tail beyond the
        // persisted count). Idempotent under re-save; safe because dispute settlement is
        // serialized by the task-row pessimistic lock + first-ruling-wins guard.
        long persisted = rulingJpa.countByDisputeId(d.id());
        List<Ruling> rulings = d.rulings();
        for (int i = (int) persisted; i < rulings.size(); i++) {
            Ruling r = rulings.get(i);
            rulingJpa.save(new DisputeRulingDO(UUID.randomUUID(), d.id(), r.tier(),
                    r.decidedBy().name(), r.category().name(), r.rationale(),
                    r.decidedAt(), Instant.now()));
        }
        return d;
    }

    @Override
    public Optional<DisputeModel> findById(UUID id) {
        return jpa.findById(id).map(this::toModel);
    }

    @Override
    public Optional<DisputeModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskId(taskId).map(this::toModel);
    }

    @Override
    public List<UUID> findStaleArbitratingIds(Instant cutoff) {
        return jpa.findStaleArbitratingIds(cutoff);
    }

    @Override
    public List<UUID> findStaleRuledIds(Instant cutoff) {
        return jpa.findStaleRuledIds(cutoff);
    }

    private DisputeModel toModel(DisputeDO e) {
        List<Ruling> rulings = rulingJpa.findByDisputeIdOrderByGmtCreateAsc(e.getId()).stream()
                .map(r -> new Ruling(r.getTier(), RulingCategory.valueOf(r.getCategory()),
                        r.getRationale(), RulingDecidedBy.valueOf(r.getDecidedBy()), r.getDecidedAt()))
                .toList();
        return DisputeModel.rehydrate(e.getId(), e.getTaskId(), e.getRaisedBy(),
                RejectReason.valueOf(e.getReasonCategory()),
                DisputeStatus.valueOf(e.getStatus()), rulings, e.getCorrelationId(),
                e.getGmtCreate(), e.getResolvedAt());
    }
}
