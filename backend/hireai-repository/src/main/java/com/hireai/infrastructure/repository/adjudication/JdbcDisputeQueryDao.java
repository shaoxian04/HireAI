package com.hireai.infrastructure.repository.adjudication;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.port.DisputeQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Read-only client-scoped dispute projection for the `/client/disputes` surface. */
@Repository
public class JdbcDisputeQueryDao implements DisputeQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDisputeQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // dispute_rulings columns are tier/category/rationale/decided_at (V21). gmt_modified is not
    // entity-maintained, so updated_at derives from the latest ruling's decided_at, else gmt_create.
    private static final String SQL = """
        SELECT d.id AS dispute_id, d.task_id, t.title AS task_title, d.status,
               lr.category AS proposed_category,
               COALESCE(lr.decided_at, d.gmt_create) AS updated_at
        FROM disputes d
        JOIN tasks t ON t.id = d.task_id
        LEFT JOIN LATERAL (
            SELECT r.category, r.decided_at FROM dispute_rulings r
            WHERE r.dispute_id = d.id ORDER BY r.tier DESC, r.decided_at DESC LIMIT 1
        ) lr ON true
        WHERE d.raised_by = :clientId
        ORDER BY CASE d.status WHEN 'RULED' THEN 0 WHEN 'ARBITRATING' THEN 1
                               WHEN 'ESCALATED' THEN 1 WHEN 'OPEN' THEN 1 ELSE 2 END,
                 COALESCE(lr.decided_at, d.gmt_create) DESC
        """;

    @Override
    public List<DisputeMineRow> findDisputesForClient(UUID clientId) {
        return jdbc.query(SQL, new MapSqlParameterSource("clientId", clientId), (rs, i) ->
                new DisputeMineRow(
                        rs.getObject("dispute_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        rs.getString("task_title"),
                        rs.getString("status"),
                        rs.getString("proposed_category"),
                        rs.getTimestamp("updated_at").toInstant()));
    }
}
