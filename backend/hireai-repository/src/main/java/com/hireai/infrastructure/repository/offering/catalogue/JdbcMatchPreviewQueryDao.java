package com.hireai.infrastructure.repository.offering.catalogue;

import com.hireai.application.port.query.MatchPreviewQueryPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * JDBC implementation of {@link MatchPreviewQueryPort}. Mirrors the catalogue card projection
 * (agents + agent_profiles + agent_versions) and adds the per-agent in_flight/sample_count
 * subqueries the matcher needs. Bookable = a.status = 'ACTIVE' AND p.is_listed (exactly the direct
 * booking requirement). No price filter — the app service partitions by budget. Category is
 * lowercased before binding (categories are stored lowercase).
 */
@Repository
public class JdbcMatchPreviewQueryDao implements MatchPreviewQueryPort {

    private static final String SQL = """
            SELECT a.id                    AS agent_id,
                   v.id                    AS agent_version_id,
                   a.name                  AS agent_name,
                   p.tagline               AS tagline,
                   p.logo_url              AS logo_url,
                   v.price                 AS price,
                   a.reputation_score      AS reputation_score,
                   v.capability_categories AS capability_categories,
                   v.output_spec::text     AS output_spec_json,
                   v.output_spec->>'format' AS output_format,
                   v.webhook_url           AS webhook_url,
                   v.max_execution_seconds AS max_execution_seconds,
                   v.max_concurrent        AS max_concurrent,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id AND t.status IN ('QUEUED','EXECUTING'))          AS in_flight,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id
                       AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION'))     AS sample_count
            FROM agents a
            JOIN agent_profiles p ON p.agent_id = a.id
            JOIN agent_versions v ON v.id = a.current_version_id
            WHERE a.status = 'ACTIVE'
              AND p.is_listed
              AND v.capability_categories && ARRAY[:category]::text[]
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMatchPreviewQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ShortlistCandidateRow> findBookableCandidates(String category) {
        var params = new MapSqlParameterSource()
                .addValue("category", category == null ? "" : category.trim().toLowerCase(Locale.ROOT));
        return jdbc.query(SQL, params, (rs, i) -> new ShortlistCandidateRow(
                rs.getObject("agent_id", UUID.class),
                rs.getObject("agent_version_id", UUID.class),
                rs.getString("agent_name"),
                rs.getString("tagline"),
                rs.getString("logo_url"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("reputation_score"),
                stringList(rs.getArray("capability_categories")),
                rs.getString("output_spec_json"),
                rs.getString("output_format"),
                rs.getString("webhook_url"),
                rs.getInt("max_execution_seconds"),
                rs.getInt("max_concurrent"),
                rs.getLong("in_flight"),
                rs.getLong("sample_count")));
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
