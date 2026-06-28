package com.hireai.infrastructure.repository.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Infrastructure impl of {@link SettlementRepository}. */
@Repository
public class SettlementRepositoryImpl implements SettlementRepository {

    private final SettlementJpaRepository jpa;

    public SettlementRepositoryImpl(SettlementJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public SettlementModel save(SettlementModel s) {
        jpa.save(new SettlementDO(s.id(), s.taskId(), s.type().name(),
                s.net().value(), s.commission().value(), s.createdAt()));
        return s;
    }

    @Override
    public Optional<SettlementModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskId(taskId).map(this::toModel);
    }

    private SettlementModel toModel(SettlementDO e) {
        return new SettlementModel(e.getId(), e.getTaskId(), SettlementType.valueOf(e.getType()),
                Money.of(e.getNet()), Money.of(e.getCommission()), e.getCreatedAt());
    }
}
