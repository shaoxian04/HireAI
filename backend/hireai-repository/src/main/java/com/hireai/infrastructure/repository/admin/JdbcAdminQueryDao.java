package com.hireai.infrastructure.repository.admin;

import com.hireai.application.biz.admin.AdminQueryPort;
import com.hireai.application.biz.admin.view.AdminViews;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read-only admin projections over disputes/tasks/results/agents/wallets/users/settlements. */
@Repository
public class JdbcAdminQueryDao implements AdminQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdminQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AdminViews.Overview overview() {
        String sql = """
                SELECT
                  (SELECT COUNT(*) FROM disputes WHERE status = 'OPEN')        AS disputes_open,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'ARBITRATING') AS disputes_arbitrating,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'ESCALATED')   AS disputes_escalated,
                  (SELECT COUNT(*) FROM disputes WHERE status = 'RESOLVED')    AS disputes_resolved,
                  (SELECT COUNT(*) FROM tasks)                                 AS tasks_total,
                  (SELECT COUNT(*) FROM users)                                 AS users_total,
                  (SELECT COUNT(*) FROM agents)                                AS agents_total,
                  (SELECT COALESCE(SUM(escrow_balance), 0) FROM wallets)       AS escrow_held,
                  (SELECT COALESCE(SUM(commission), 0) FROM settlements)       AS commission_earned
                """;
        return jdbc.queryForObject(sql, Map.of(), (rs, i) -> new AdminViews.Overview(
                rs.getLong("disputes_open"), rs.getLong("disputes_arbitrating"),
                rs.getLong("disputes_escalated"), rs.getLong("disputes_resolved"),
                rs.getLong("tasks_total"), rs.getLong("users_total"), rs.getLong("agents_total"),
                rs.getBigDecimal("escrow_held"), rs.getBigDecimal("commission_earned")));
    }

    @Override
    public List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly) {
        String filter = needsAttentionOnly ? " WHERE d.status IN ('OPEN','ESCALATED') " : " ";
        String sql = """
                SELECT d.id AS dispute_id, d.task_id, t.title AS task_title, d.status,
                       d.reason_category, d.gmt_create,
                       split_part(u.email, '@', 1) AS client_name,
                       EXISTS (SELECT 1 FROM dispute_rulings r
                               WHERE r.dispute_id = d.id AND r.decided_by = 'ARBITRATOR') AS has_arbitrator_ruling
                FROM disputes d
                JOIN tasks t ON t.id = d.task_id
                JOIN users u ON u.id = d.raised_by
                """ + filter + " ORDER BY d.gmt_create ASC";
        return jdbc.query(sql, Map.of(), (rs, i) -> {
            String status = rs.getString("status");
            boolean needsAttention = "OPEN".equals(status) || "ESCALATED".equals(status);
            return new AdminViews.DisputeRow(
                    rs.getObject("dispute_id", UUID.class), rs.getObject("task_id", UUID.class),
                    rs.getString("task_title"), status, rs.getString("reason_category"),
                    toInstant(rs.getTimestamp("gmt_create")), rs.getString("client_name"),
                    rs.getBoolean("has_arbitrator_ruling"), needsAttention);
        });
    }

    @Override
    public Optional<AdminViews.Evidence> disputeEvidence(UUID taskId) {
        String sql = """
                SELECT t.id AS task_id, t.title, t.description AS task_description,
                       split_part(u.email, '@', 1) AS client_name,
                       av.output_spec::text AS output_spec_json,
                       tr.result_payload::text AS result_payload_json, tr.result_url, tr.agent_status
                FROM tasks t
                JOIN users u ON u.id = t.client_id
                LEFT JOIN agent_versions av ON av.id = t.agent_version_id
                LEFT JOIN task_results tr ON tr.task_id = t.id
                WHERE t.id = :taskId
                """;
        var params = new MapSqlParameterSource().addValue("taskId", taskId);
        List<AdminViews.Evidence> rows = jdbc.query(sql, params, (rs, i) -> new AdminViews.Evidence(
                rs.getObject("task_id", UUID.class), rs.getString("title"), rs.getString("task_description"),
                rs.getString("client_name"), rs.getString("output_spec_json"),
                rs.getString("result_payload_json"), rs.getString("result_url"), rs.getString("agent_status")));
        return rows.stream().findFirst();
    }

    @Override
    public List<AdminViews.TaskRow> recentTasks(int limit) {
        int bounded = Math.min(Math.max(limit, 1), 200);
        String sql = """
                SELECT t.id, t.title, t.status, t.budget,
                       split_part(u.email, '@', 1) AS client_name, t.gmt_create
                FROM tasks t JOIN users u ON u.id = t.client_id
                ORDER BY t.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("limit", bounded);
        return jdbc.query(sql, params, (rs, i) -> new AdminViews.TaskRow(
                rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("status"),
                rs.getBigDecimal("budget"), rs.getString("client_name"),
                toInstant(rs.getTimestamp("gmt_create"))));
    }

    @Override
    public List<AdminViews.UserRow> usersWithWallets() {
        String sql = """
                SELECT u.id, split_part(u.email, '@', 1) AS name, u.email,
                       COALESCE(array_agg(ur.role ORDER BY ur.role) FILTER (WHERE ur.role IS NOT NULL), '{}') AS roles,
                       COALESCE(w.available_balance, 0) AS available_balance,
                       COALESCE(w.escrow_balance, 0) AS escrow_balance
                FROM users u
                LEFT JOIN user_roles ur ON ur.user_id = u.id
                LEFT JOIN wallets w ON w.user_id = u.id
                GROUP BY u.id, u.email, w.available_balance, w.escrow_balance
                ORDER BY u.gmt_create DESC
                """;
        return jdbc.query(sql, Map.of(), (rs, i) -> new AdminViews.UserRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"),
                stringList(rs.getArray("roles")), rs.getBigDecimal("available_balance"),
                rs.getBigDecimal("escrow_balance")));
    }

    @Override
    public List<AdminViews.AgentRow> agents() {
        String sql = """
                SELECT a.id, a.name, a.status, split_part(u.email, '@', 1) AS builder_name,
                       a.reputation_score, v.price
                FROM agents a
                JOIN users u ON u.id = a.owner_id
                LEFT JOIN agent_versions v ON v.id = a.current_version_id
                ORDER BY a.gmt_create DESC
                """;
        return jdbc.query(sql, Map.of(), (rs, i) -> new AdminViews.AgentRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                rs.getString("builder_name"), rs.getBigDecimal("reputation_score"),
                rs.getBigDecimal("price")));
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
