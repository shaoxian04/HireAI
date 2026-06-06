package com.hireai.infrastructure.repository.catalogue;

import com.hireai.application.port.query.BuilderStatsQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Builder stats over tasks joined through agent_versions. "creditsInEscrow" = budgets of
 * tasks still holding escrow (no settlement exists yet, so RESULT_RECEIVED still holds);
 * failed/timed-out/spec-violation are excluded as future-refund, RESOLVED/CANCELLED as exits.
 * Labelled in the UI as pending Module 5. Turnaround = received_at - task creation.
 * avg_turnaround_seconds is read via Number to handle both Double and BigDecimal JDBC mappings.
 */
@Repository
public class JdbcBuilderStatsQueryDao implements BuilderStatsQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBuilderStatsQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StatsRow stats(UUID agentId) {
        String sql = """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE tk.status IN ('RESULT_RECEIVED','RESOLVED')) AS completed,
                       COUNT(*) FILTER (WHERE tk.status IN ('FAILED','TIMED_OUT','SPEC_VIOLATION')) AS failed,
                       COUNT(*) FILTER (WHERE tk.status IN
                           ('SUBMITTED','QUEUED','EXECUTING','AWAITING_CAPACITY','PENDING_REVIEW')) AS open_count,
                       COALESCE(SUM(tk.budget) FILTER (WHERE tk.status NOT IN
                           ('RESOLVED','CANCELLED','FAILED','TIMED_OUT','SPEC_VIOLATION')), 0) AS credits_in_escrow,
                       COALESCE(SUM(tk.budget) FILTER (WHERE tk.status IN
                           ('RESULT_RECEIVED','RESOLVED')), 0) AS potential_earnings,
                       AVG(EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create))) AS avg_turnaround_seconds,
                       COUNT(*) FILTER (WHERE tr.received_at IS NOT NULL
                           AND EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create)) <= v.max_execution_seconds) AS on_time,
                       COUNT(*) FILTER (WHERE tr.received_at IS NOT NULL) AS with_result
                FROM tasks tk
                JOIN agent_versions v ON v.id = tk.agent_version_id
                LEFT JOIN task_results tr ON tr.task_id = tk.id
                WHERE v.agent_id = :agentId
                """;
        return jdbc.queryForObject(sql, new MapSqlParameterSource("agentId", agentId),
                (rs, i) -> {
                    Number n = (Number) rs.getObject("avg_turnaround_seconds");
                    Double avgTurnaround = n == null ? null : n.doubleValue();
                    return new StatsRow(
                            rs.getInt("total"),
                            rs.getInt("completed"),
                            rs.getInt("failed"),
                            rs.getInt("open_count"),
                            rs.getBigDecimal("credits_in_escrow"),
                            rs.getBigDecimal("potential_earnings"),
                            avgTurnaround,
                            rs.getInt("on_time"),
                            rs.getInt("with_result"));
                });
    }

    @Override
    public List<TrendPointRow> trend(UUID agentId, int days) {
        String sql = """
                SELECT date_trunc('day', tk.gmt_create)::date AS day, COUNT(*) AS cnt
                FROM tasks tk JOIN agent_versions v ON v.id = tk.agent_version_id
                WHERE v.agent_id = :agentId AND tk.gmt_create > now() - make_interval(days => :days)
                GROUP BY day ORDER BY day
                """;
        var params = new MapSqlParameterSource()
                .addValue("agentId", agentId)
                .addValue("days", days);
        return jdbc.query(sql, params, (rs, i) ->
                new TrendPointRow(rs.getDate("day").toLocalDate(), rs.getInt("cnt")));
    }

    @Override
    public List<RecentTaskRow> recentTasks(UUID agentId, int limit) {
        String sql = """
                SELECT tk.id, tk.title, tk.status, tk.gmt_create
                FROM tasks tk JOIN agent_versions v ON v.id = tk.agent_version_id
                WHERE v.agent_id = :agentId
                ORDER BY tk.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource()
                .addValue("agentId", agentId)
                .addValue("limit", Math.min(Math.max(limit, 1), 50));
        return jdbc.query(sql, params, (rs, i) -> new RecentTaskRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("status"),
                rs.getTimestamp("gmt_create").toInstant()));
    }
}
