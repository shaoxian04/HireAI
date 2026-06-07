package com.hireai.infrastructure.repository.catalogue;

import com.hireai.application.port.query.BuilderEarningsQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Earnings rows for the builder console. Deliberately dumb SQL: just the ownership join —
 * status/resolution filtering and ALL money arithmetic live in the app service
 * (BuilderEarningsReadAppServiceImpl) so the semantics are unit-testable. The JOIN through
 * tasks.agent_version_id naturally excludes tasks never routed to an agent.
 */
@Repository
public class JdbcBuilderEarningsQueryDao implements BuilderEarningsQueryPort {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBuilderEarningsQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RoutedTaskRow> routedTasks(UUID ownerId) {
        String sql = """
                SELECT tk.id AS task_id, tk.title, tk.budget, tk.status, tk.resolution,
                       tk.resolved_at, a.id AS agent_id, a.name AS agent_name
                FROM tasks tk
                JOIN agent_versions v ON v.id = tk.agent_version_id
                JOIN agents a ON a.id = v.agent_id
                WHERE a.owner_id = :ownerId
                """;
        return jdbc.query(sql, new MapSqlParameterSource("ownerId", ownerId), (rs, i) -> {
            Timestamp resolvedAt = rs.getTimestamp("resolved_at");
            return new RoutedTaskRow(
                    rs.getObject("task_id", UUID.class),
                    rs.getString("title"),
                    rs.getBigDecimal("budget"),
                    rs.getString("status"),
                    rs.getString("resolution"),
                    resolvedAt == null ? null : resolvedAt.toInstant(),
                    rs.getObject("agent_id", UUID.class),
                    rs.getString("agent_name"));
        });
    }

    @Override
    public List<OwnedAgentRow> ownedAgents(UUID ownerId) {
        String sql = "SELECT id, name FROM agents WHERE owner_id = :ownerId ORDER BY name";
        return jdbc.query(sql, new MapSqlParameterSource("ownerId", ownerId), (rs, i) ->
                new OwnedAgentRow(rs.getObject("id", UUID.class), rs.getString("name")));
    }
}
