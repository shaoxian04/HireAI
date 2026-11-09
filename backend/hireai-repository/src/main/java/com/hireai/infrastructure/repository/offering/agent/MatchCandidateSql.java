package com.hireai.infrastructure.repository.offering.agent;

/**
 * The one SQL definition of which Agents the matcher may consider, shared by the web shortlist
 * ({@code JdbcMatchPreviewQueryDao}) and routed selection / failover
 * ({@link AgentVersionJpaRepository#findActiveCandidates}), so the two can't drift (#81).
 *
 * <p>Eligible = the agent is {@code ACTIVE}, its storefront is listed, and its current version covers
 * {@code :category}. Budget is applied by the ranking domain service ({@code price <= budget}); the
 * routed query also filters on price in SQL, which never changes the result. Everything here is a
 * compile-time constant so it can sit inside a {@code @Query} annotation.
 *
 * <p>The load columns feed the score's load and exploration terms: in_flight counts QUEUED/EXECUTING
 * tasks and sample_count counts RESOLVED/FAILED/TIMED_OUT/SPEC_VIOLATION tasks, per agent across all
 * its versions.
 */
public final class MatchCandidateSql {

    /** Select-list columns the score needs beyond the version's own (aliases a, v). */
    public static final String LOAD_COLUMNS = """
                   v.max_concurrent        AS max_concurrent,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id AND t.status IN ('QUEUED','EXECUTING'))          AS in_flight,
                   (SELECT COUNT(*) FROM tasks t
                      JOIN agent_versions av ON av.id = t.agent_version_id
                     WHERE av.agent_id = a.id
                       AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION'))     AS sample_count
            """;

    /** FROM + WHERE of the eligible set; binds {@code :category} (lowercase slug). Aliases a, v, p. */
    public static final String ELIGIBLE_FROM = """
            FROM agents a
            JOIN agent_versions v ON v.id = a.current_version_id
            JOIN agent_profiles p ON p.agent_id = a.id
            WHERE a.status = 'ACTIVE'
              AND p.is_listed
              AND v.capability_categories && ARRAY[:category]::text[]
            """;

    private MatchCandidateSql() {
    }
}
