package com.hireai.infrastructure.repository.adjudication;

import com.hireai.domain.biz.adjudication.enums.DisputeStatus;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.adjudication.enums.RulingDecidedBy;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class DisputeRepositoryImpl implements DisputeRepository {

    private final DisputeJpaRepository jpa;

    public DisputeRepositoryImpl(DisputeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public DisputeModel save(DisputeModel d) {
        Ruling r = d.ruling();
        jpa.save(new DisputeDO(
                d.id(), d.taskId(), d.raisedBy(), d.reasonCategory().name(), d.status().name(),
                d.correlationId(),
                r == null ? null : r.category().name(),
                r == null ? null : r.rationale(),
                r == null ? null : r.tier(),
                r == null ? null : r.decidedBy().name(),
                d.resolvedAt(), d.createdAt()));
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

    private DisputeModel toModel(DisputeDO e) {
        Ruling ruling = e.getRulingCategory() == null ? null : new Ruling(
                e.getRulingTier() == null ? 1 : e.getRulingTier(),
                RulingCategory.valueOf(e.getRulingCategory()),
                e.getRulingRationale(),
                RulingDecidedBy.valueOf(e.getDecidedBy()));
        return DisputeModel.rehydrate(
                e.getId(), e.getTaskId(), e.getRaisedBy(),
                RejectReason.valueOf(e.getReasonCategory()),
                DisputeStatus.valueOf(e.getStatus()), ruling, e.getCorrelationId(),
                e.getGmtCreate(), e.getResolvedAt());
    }
}
