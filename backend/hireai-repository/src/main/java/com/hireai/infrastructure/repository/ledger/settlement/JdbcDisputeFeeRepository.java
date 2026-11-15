package com.hireai.infrastructure.repository.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.enums.DisputeFeeType;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.repository.DisputeFeeRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** {@code dispute_fees} (V45): append-only, guarded by DB triggers. */
@Repository
public class JdbcDisputeFeeRepository implements DisputeFeeRepository {

    private final JdbcTemplate jdbc;

    public JdbcDisputeFeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(UUID disputeId, UUID taskId, DisputeFeeCharge c) {
        jdbc.update("""
                INSERT INTO dispute_fees (id, dispute_id, task_id, fee_type, full_cost, capped_amount,
                                          client_charged, builder_charged, platform_covered)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), disputeId, taskId, c.type().name(), c.fullCost().value(),
                c.cappedAmount().value(), c.clientCharged().value(), c.builderCharged().value(),
                c.platformCovered().value());
    }

    @Override
    public List<DisputeFeeCharge> findByDisputeId(UUID disputeId) {
        return jdbc.query("""
                SELECT fee_type, full_cost, capped_amount, client_charged, builder_charged, platform_covered
                FROM dispute_fees WHERE dispute_id = ?
                ORDER BY CASE fee_type WHEN 'ARBITRATION_COST' THEN 0 ELSE 1 END""",
                (rs, i) -> new DisputeFeeCharge(DisputeFeeType.valueOf(rs.getString("fee_type")),
                        Money.of(rs.getBigDecimal("full_cost")), Money.of(rs.getBigDecimal("capped_amount")),
                        Money.of(rs.getBigDecimal("client_charged")), Money.of(rs.getBigDecimal("builder_charged")),
                        Money.of(rs.getBigDecimal("platform_covered"))),
                disputeId);
    }
}
