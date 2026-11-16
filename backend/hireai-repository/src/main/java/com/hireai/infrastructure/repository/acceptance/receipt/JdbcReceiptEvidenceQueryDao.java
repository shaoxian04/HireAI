package com.hireai.infrastructure.repository.acceptance.receipt;

import com.hireai.application.port.query.ReceiptEvidenceQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** The settlement and ledger reads behind receipts (V49). */
@Repository
public class JdbcReceiptEvidenceQueryDao implements ReceiptEvidenceQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcReceiptEvidenceQueryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SettledSubject> settledWithoutReceipt(int limit) {
        return jdbc.query("""
                SELECT task_id, item_no FROM (
                    SELECT s.task_id, NULL::int AS item_no, s.created_at FROM settlements s
                    WHERE NOT EXISTS (SELECT 1 FROM receipts r WHERE r.task_id = s.task_id AND r.item_no IS NULL)
                    UNION ALL
                    SELECT i.task_id, i.item_no, i.created_at FROM task_item_settlements i
                    WHERE NOT EXISTS (SELECT 1 FROM receipts r WHERE r.task_id = i.task_id AND r.item_no = i.item_no)
                      -- A sampled Task's Item receipts wait for its inspection to end (spec S8, D6).
                      AND NOT EXISTS (SELECT 1 FROM task_inspections ti JOIN tasks t ON t.id = ti.task_id
                                      WHERE ti.task_id = i.task_id AND ti.status IN ('SAMPLING', 'REWORKING')
                                        AND t.status NOT IN ('RESOLVED', 'TIMED_OUT', 'SPEC_VIOLATION', 'FAILED',
                                                             'CANCELLED'))
                ) pending
                ORDER BY created_at, task_id, item_no
                LIMIT ?""",
                (rs, i) -> new SettledSubject(rs.getObject("task_id", UUID.class), (Integer) rs.getObject("item_no")),
                limit);
    }

    @Override
    public List<LedgerLine> ledgerEntries(UUID taskId, String correlationId) {
        return jdbc.query("""
                SELECT e.id, w.user_id, e.entry_type, e.amount, e.created_at
                FROM ledger_entries e JOIN wallets w ON w.id = e.wallet_id
                WHERE e.related_task_id = ? AND e.correlation_id = ?
                ORDER BY e.created_at, e.entry_type, e.id""",
                (rs, i) -> new LedgerLine(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("entry_type"), rs.getBigDecimal("amount"),
                        rs.getTimestamp("created_at").toInstant()),
                taskId, correlationId);
    }
}
