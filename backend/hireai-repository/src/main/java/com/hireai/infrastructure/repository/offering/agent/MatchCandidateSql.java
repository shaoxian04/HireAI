package com.hireai.infrastructure.repository.offering.agent;

/**
 * The one SQL definition of which Agents the matcher may consider, shared by the web shortlist
 * ({@code JdbcMatchPreviewQueryDao}) and routed selection / failover
 * ({@link AgentVersionJpaRepository#findActiveCandidates}), so the two can't drift (#81).
 *
 * <p>Eligible = the agent is {@code ACTIVE}, its storefront is listed, its current version covers
 * {@code :category}, and, when the Client set a deadline, the version's max execution time is at most
 * {@code :longestExecutionSeconds} (#83). The caller derives that bound from the deadline at the
 * moment of selection ({@code ClientDeadline.longestExecutionSeconds}: deadline - now - grace), so this
 * is exactly {@code now + max_execution_seconds + grace <= client_deadline}; a null bound means no
 * deadline. Budget is applied by the ranking domain service ({@code price <= budget}); the
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

    /**
     * FROM + WHERE of the eligible set; binds {@code :category} (lowercase slug) and
     * {@code :longestExecutionSeconds} (bigint, null = no client deadline). Aliases a, v, p.
     */
    public static final String ELIGIBLE_FROM = """
            FROM agents a
            JOIN agent_versions v ON v.id = a.current_version_id
            JOIN agent_profiles p ON p.agent_id = a.id
            WHERE a.status = 'ACTIVE'
              AND p.is_listed
              AND v.capability_categories && ARRAY[:category]::text[]
              AND (CAST(:longestExecutionSeconds AS bigint) IS NULL
                   OR v.max_execution_seconds <= CAST(:longestExecutionSeconds AS bigint))
            """;

    private MatchCandidateSql() {
    }
}
