package com.hireai.infrastructure.repository.apikey;

import com.hireai.application.port.query.SpendReadPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * CQRS spend reads. Terminal money-released states (escrow already left) are excluded from the
 * concurrent sum; the daily sum counts every attributed submission in the window regardless of
 * outcome (velocity control). Mirrors the JdbcMatchPreviewQueryDao style.
 */
@Repository
public class JdbcSpendReadDao implements SpendReadPort {

    private static final String COMMITTED_SQL = """
            SELECT COALESCE(SUM(akt.budget), 0)
            FROM api_key_task akt
            JOIN tasks t ON t.id = akt.task_id
            WHERE akt.api_key_id = :keyId
              AND t.status NOT IN ('RESOLVED','SPEC_VIOLATION','TIMED_OUT','FAILED','CANCELLED')
            """;

    private static final String DAILY_SQL = """
            SELECT COALESCE(SUM(budget), 0)
            FROM api_key_task
            WHERE api_key_id = :keyId AND created_at > :since
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSpendReadDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal committedFor(UUID apiKeyId) {
        return jdbc.queryForObject(COMMITTED_SQL,
                new MapSqlParameterSource("keyId", apiKeyId), BigDecimal.class);
    }

    @Override
    public BigDecimal dailySpendFor(UUID apiKeyId, Instant since) {
        var params = new MapSqlParameterSource()
                .addValue("keyId", apiKeyId)
                .addValue("since", Timestamp.from(since));
        return jdbc.queryForObject(DAILY_SQL, params, BigDecimal.class);
    }
}
