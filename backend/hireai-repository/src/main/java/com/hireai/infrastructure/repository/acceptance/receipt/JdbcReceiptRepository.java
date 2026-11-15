package com.hireai.infrastructure.repository.acceptance.receipt;

import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;
import com.hireai.domain.biz.acceptance.receipt.repository.ReceiptRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code receipts} (V48): append-only (DB triggers), one row per settled subject. */
@Repository
public class JdbcReceiptRepository implements ReceiptRepository {

    private static final String COLUMNS = "id, task_id, item_no, key_id, body, signature, issued_at";

    private static final RowMapper<ReceiptModel> ROW = (rs, i) -> new ReceiptModel(
            rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
            (Integer) rs.getObject("item_no"), rs.getString("key_id"), rs.getString("body"),
            rs.getString("signature"), rs.getTimestamp("issued_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(ReceiptModel r) {
        return jdbc.update("INSERT INTO receipts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                r.id(), r.taskId(), r.itemNo(), r.keyId(), r.body(), r.signature(),
                Timestamp.from(r.issuedAt())) == 1;
    }

    @Override
    public Optional<ReceiptModel> findById(UUID receiptId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM receipts WHERE id = ?", ROW, receiptId).stream().findFirst();
    }

    @Override
    public List<ReceiptModel> findByTaskId(UUID taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM receipts WHERE task_id = ? ORDER BY COALESCE(item_no, 0)",
                ROW, taskId);
    }
}
