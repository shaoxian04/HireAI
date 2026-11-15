package com.hireai.infrastructure.repository.ledger.settlement;

import com.hireai.domain.biz.ledger.settlement.enums.SettlementType;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.ItemSettlementRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JDBC impl of {@link ItemSettlementRepository} over the append-only {@code task_item_settlements} (V42). */
@Repository
public class ItemSettlementRepositoryImpl implements ItemSettlementRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ItemSettlementRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(ItemSettlementModel s) {
        jdbc.update("""
                INSERT INTO task_item_settlements (id, task_id, item_id, item_no, type, net, commission, refund,
                                                   created_at)
                VALUES (:id, :taskId, :itemId, :itemNo, :type, :net, :commission, :refund, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", s.id())
                .addValue("taskId", s.taskId())
                .addValue("itemId", s.itemId())
                .addValue("itemNo", s.itemNo())
                .addValue("type", s.type().name())
                .addValue("net", s.net().value())
                .addValue("commission", s.commission().value())
                .addValue("refund", s.refund().value())
                .addValue("createdAt", Timestamp.from(s.createdAt())));
    }

    @Override
    public List<ItemSettlementModel> findByTaskId(UUID taskId) {
        return jdbc.query("""
                SELECT id, task_id, item_id, item_no, type, net, commission, refund, created_at
                FROM task_item_settlements WHERE task_id = :taskId ORDER BY item_no
                """, Map.of("taskId", taskId),
                (rs, i) -> new ItemSettlementModel(rs.getObject("id", UUID.class),
                        rs.getObject("task_id", UUID.class), rs.getObject("item_id", UUID.class),
                        rs.getInt("item_no"), SettlementType.valueOf(rs.getString("type")),
                        Money.of(rs.getBigDecimal("net")), Money.of(rs.getBigDecimal("commission")),
                        Money.of(rs.getBigDecimal("refund")), rs.getTimestamp("created_at").toInstant()));
    }
}
