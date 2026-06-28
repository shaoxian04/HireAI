package com.hireai.infrastructure.repository.offering.catalogue;

import com.hireai.application.port.query.CatalogueQueryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only catalogue projection over agents/profiles/versions/reviews/tasks. ORDER BY comes
 * from the SORTS whitelist — user input is bound as parameters only. hot = featured pin, then
 * reputation*0.5 + 14d-request-count*8 + (+10 if a request in the last 3 days) (spec §4.5).
 */
@Repository
public class JdbcCatalogueQueryDao implements CatalogueQueryPort {

    /**
     * Whitelisted ORDER BY expressions. Column references are qualified to avoid ambiguity
     * between SELECT aliases and raw column names in the same query.
     */
    private static final Map<String, String> SORTS = Map.of(
            "hot",       "p.is_featured DESC, hot_score DESC, a.gmt_create DESC",
            "rating",    "rating_avg DESC NULLS LAST, rating_count DESC, a.gmt_create DESC",
            "price_asc", "v.price ASC, a.gmt_create DESC",
            "price_desc","v.price DESC, a.gmt_create DESC",
            "newest",    "a.gmt_create DESC");

    private static final String CARD_SELECT = """
            SELECT a.id, a.name, split_part(u.email, '@', 1) AS builder_name,
                   a.reputation_score, a.gmt_create,
                   p.tagline, p.logo_url, p.cover_url, p.is_featured,
                   v.capability_categories, v.price, v.max_execution_seconds,
                   r.rating_avg, COALESCE(r.rating_count, 0) AS rating_count,
                   COALESCE(t.request_count, 0) AS request_count,
                   (a.reputation_score * 0.5
                    + COALESCE(t.recent_count, 0) * 8
                    + CASE WHEN t.last_request_at > now() - INTERVAL '3 days' THEN 10 ELSE 0 END
                    + CASE WHEN p.is_featured THEN 1000 ELSE 0 END
                   ) AS hot_score
            FROM agents a
            JOIN users u          ON u.id = a.owner_id
            JOIN agent_profiles p ON p.agent_id = a.id
            JOIN agent_versions v ON v.id = a.current_version_id
            LEFT JOIN (
                SELECT agent_id, AVG(rating)::numeric(3,2) AS rating_avg, COUNT(*) AS rating_count
                FROM reviews WHERE is_published GROUP BY agent_id
            ) r ON r.agent_id = a.id
            LEFT JOIN (
                SELECT av.agent_id,
                       COUNT(*) AS request_count,
                       COUNT(*) FILTER (WHERE tk.gmt_create > now() - INTERVAL '14 days') AS recent_count,
                       MAX(tk.gmt_create) AS last_request_at
                FROM tasks tk JOIN agent_versions av ON av.id = tk.agent_version_id
                GROUP BY av.agent_id
            ) t ON t.agent_id = a.id
            WHERE a.status = 'ACTIVE' AND p.is_listed
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCatalogueQueryDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AgentCardRow> searchCards(String q, String category, String sort, int page, int size) {
        String orderBy = SORTS.getOrDefault(sort == null ? "hot" : sort, SORTS.get("hot"));
        // Clamp to [1,100] — mirrors AgentQuery/TaskQuery convention in the application layer.
        int bounded = Math.min(Math.max(size, 1), 100);
        String sql = CARD_SELECT + """
                  AND (:q = '' OR a.name ILIKE '%' || :q || '%'
                       OR split_part(u.email, '@', 1) ILIKE '%' || :q || '%')
                  /* NOTE: % and _ in :q act as ILIKE wildcards — intentional; callers may sanitise if needed */
                  AND (:category = '' OR v.capability_categories @> ARRAY[:category]::text[])
                ORDER BY """ + " " + orderBy + " LIMIT :size OFFSET :offset";
        var params = new MapSqlParameterSource()
                .addValue("q", q == null ? "" : q.trim())
                .addValue("category", category == null ? "" : category.trim().toLowerCase())
                .addValue("size", bounded)
                .addValue("offset", Math.max(page, 0) * bounded);
        return jdbc.query(sql, params, cardMapper());
    }

    @Override
    public Optional<AgentProfileRow> findProfile(UUID agentId) {
        String sql = """
                SELECT a.id, a.name, split_part(u.email, '@', 1) AS builder_name,
                       a.reputation_score, a.gmt_create,
                       p.tagline, p.logo_url, p.cover_url, p.is_featured,
                       p.description, p.sample_output, p.gallery_urls,
                       v.capability_categories, v.price, v.max_execution_seconds,
                       v.output_spec::text AS output_spec_json,
                       r.rating_avg, COALESCE(r.rating_count, 0) AS rating_count,
                       COALESCE(s.request_count, 0) AS request_count,
                       COALESCE(s.completed_count, 0) AS completed_count,
                       s.avg_turnaround_seconds
                FROM agents a
                JOIN users u          ON u.id = a.owner_id
                JOIN agent_profiles p ON p.agent_id = a.id
                JOIN agent_versions v ON v.id = a.current_version_id
                LEFT JOIN (
                    SELECT agent_id, AVG(rating)::numeric(3,2) AS rating_avg, COUNT(*) AS rating_count
                    FROM reviews WHERE is_published GROUP BY agent_id
                ) r ON r.agent_id = a.id
                LEFT JOIN (
                    SELECT av.agent_id,
                           COUNT(*) AS request_count,
                           COUNT(*) FILTER (WHERE tk.status IN ('RESULT_RECEIVED','RESOLVED')) AS completed_count,
                           AVG(EXTRACT(EPOCH FROM (tr.received_at - tk.gmt_create))) AS avg_turnaround_seconds
                    FROM tasks tk
                    JOIN agent_versions av ON av.id = tk.agent_version_id
                    LEFT JOIN task_results tr ON tr.task_id = tk.id
                    GROUP BY av.agent_id
                ) s ON s.agent_id = a.id
                WHERE a.id = :agentId AND a.status = 'ACTIVE' AND p.is_listed
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId);
        List<AgentProfileRow> rows = jdbc.query(sql, params, (rs, i) -> {
            // AVG(EXTRACT(EPOCH ...)) comes back as BigDecimal, not Double — convert via Number.
            Number turnaround = (Number) rs.getObject("avg_turnaround_seconds");
            return new AgentProfileRow(
                    mapCard(rs),
                    rs.getString("description"),
                    rs.getString("sample_output"),
                    stringList(rs.getArray("gallery_urls")),
                    rs.getString("output_spec_json"),
                    rs.getInt("completed_count"),
                    turnaround == null ? null : turnaround.doubleValue());
        });
        return rows.stream().findFirst();
    }

    @Override
    public List<CategoryCountRow> categoryCounts() {
        String sql = """
                SELECT cat AS category, COUNT(DISTINCT a.id) AS agent_count
                FROM agents a
                JOIN agent_profiles p ON p.agent_id = a.id
                JOIN agent_versions v ON v.id = a.current_version_id
                CROSS JOIN LATERAL unnest(v.capability_categories) AS cat
                WHERE a.status = 'ACTIVE' AND p.is_listed
                GROUP BY cat
                ORDER BY agent_count DESC, cat ASC
                """;
        return jdbc.query(sql, Map.of(),
                (rs, i) -> new CategoryCountRow(rs.getString("category"), rs.getInt("agent_count")));
    }

    @Override
    public List<ReviewRow> reviewsForAgent(UUID agentId, int limit) {
        // Clamp to [1,50] — mirrors AgentQuery/TaskQuery clamp convention in the application layer.
        int bounded = Math.min(Math.max(limit, 1), 50);
        String sql = """
                SELECT r.id, r.rating, r.review_text, r.builder_response,
                       split_part(u.email, '@', 1) AS author, r.gmt_create
                FROM reviews r JOIN users u ON u.id = r.client_id
                WHERE r.agent_id = :agentId AND r.is_published
                ORDER BY r.gmt_create DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("agentId", agentId).addValue("limit", bounded);
        return jdbc.query(sql, params, (rs, i) -> new ReviewRow(
                rs.getObject("id", UUID.class), rs.getInt("rating"), rs.getString("review_text"),
                rs.getString("builder_response"), rs.getString("author"),
                toInstant(rs.getTimestamp("gmt_create"))));
    }

    private RowMapper<AgentCardRow> cardMapper() {
        return (rs, i) -> mapCard(rs);
    }

    private AgentCardRow mapCard(ResultSet rs) throws SQLException {
        return new AgentCardRow(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("builder_name"),
                rs.getBigDecimal("reputation_score"), rs.getString("tagline"),
                rs.getString("logo_url"), rs.getString("cover_url"), rs.getBoolean("is_featured"),
                stringList(rs.getArray("capability_categories")), rs.getBigDecimal("price"),
                rs.getInt("max_execution_seconds"), rs.getBigDecimal("rating_avg"),
                rs.getInt("rating_count"), rs.getInt("request_count"),
                toInstant(rs.getTimestamp("gmt_create")));
    }

    private static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    /** Null-safe Timestamp → Instant conversion. */
    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
