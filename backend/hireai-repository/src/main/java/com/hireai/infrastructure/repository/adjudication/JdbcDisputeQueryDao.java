package com.hireai.infrastructure.repository.adjudication;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Read-only, user-scoped dispute projections for the `/client/disputes` and `/builder/disputes` surfaces. */
@Repository
public class JdbcDisputeQueryDao implements DisputeQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDisputeQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // dispute_rulings columns are tier/category/rationale/decided_at (V21). gmt_modified is not
    // entity-maintained, so updated_at derives from the latest ruling's decided_at, else gmt_create.
    // awaiting_you: the arbitrator's proposal goes against the viewer's side (#96), which has neither
    // accepted nor appealed it. The %s slots are the side's ruling categories and acceptance column.
    private static final String SQL = """
        SELECT d.id AS dispute_id, d.task_id, t.title AS task_title, d.status,
               lr.category AS proposed_category,
               COALESCE(lr.decided_at, d.gmt_create) AS updated_at,
               (d.status = 'RULED' AND d.appealed_by IS NULL AND lr.tier = 1
                    AND lr.category IN (%s) AND d.%s IS NULL) AS awaiting_you
        FROM disputes d
        JOIN tasks t ON t.id = d.task_id
        LEFT JOIN LATERAL (
            SELECT r.category, r.decided_at, r.tier FROM dispute_rulings r
            WHERE r.dispute_id = d.id ORDER BY r.tier DESC, r.decided_at DESC LIMIT 1
        ) lr ON true
        WHERE %s
        ORDER BY awaiting_you DESC,
                 CASE d.status WHEN 'RULED' THEN 0 WHEN 'ARBITRATING' THEN 1
                               WHEN 'ESCALATED' THEN 1 WHEN 'OPEN' THEN 1 ELSE 2 END,
                 COALESCE(lr.decided_at, d.gmt_create) DESC
        """;

    private static final String CLIENT_SQL = SQL.formatted(
            "'FULFILLED', 'PARTIALLY_FULFILLED'", "client_accepted_at", "d.raised_by = :userId");

    private static final String BUILDER_SQL = SQL.formatted(
            "'NOT_FULFILLED', 'PARTIALLY_FULFILLED'", "builder_accepted_at",
            "t.agent_version_id IN (SELECT v.id FROM agent_versions v JOIN agents a ON a.id = v.agent_id "
                    + "WHERE a.owner_id = :userId)");

    @Override
    public List<DisputeMineRow> findDisputesForClient(UUID clientId) {
        return query(CLIENT_SQL, clientId);
    }

    @Override
    public List<DisputeMineRow> findDisputesForBuilder(UUID builderId) {
        return query(BUILDER_SQL, builderId);
    }

    private List<DisputeMineRow> query(String sql, UUID userId) {
        return jdbc.query(sql, new MapSqlParameterSource("userId", userId), (rs, i) ->
                new DisputeMineRow(
                        rs.getObject("dispute_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("task_title"),
                        rs.getString("status"),
                        rs.getString("proposed_category"),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("awaiting_you")));
    }
}
