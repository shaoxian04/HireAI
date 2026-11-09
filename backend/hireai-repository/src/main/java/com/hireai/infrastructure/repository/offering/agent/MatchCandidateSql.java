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
 *
 * <p>Relevance (#85): {@code similarity} is the RAW cosine similarity ({@code 1 - (a <=> b)}) between
 * the task text's vector, bound as pgvector text in {@code :taskVector}, and the Agent's profile vector
 * of the same model ({@code :embeddingModel}); vectors from different models are never compared. It is
 * NULL when either vector is missing. The relevance floor τ and the
 * normalisation are applied by the ranking domain service, which also needs the below-floor rows to
 * tell the shortlist "no close match" apart from "no agent". No ANN index: the cosine runs only over
 * the rows the eligibility WHERE already kept.
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
                       AND t.status IN ('RESOLVED','FAILED','TIMED_OUT','SPEC_VIOLATION'))     AS sample_count,
                   1 - (e.embedding <=> CAST(NULLIF(:taskVector, '') AS vector))                           AS similarity
            """;

    /**
     * FROM + WHERE of the eligible set; binds {@code :category} (lowercase slug),
     * {@code :longestExecutionSeconds} (bigint, null = no client deadline), and for the similarity
     * column {@code :taskVector} (pgvector text) and {@code :embeddingModel} (its model); bind
     * {@link #NO_VECTOR} to both when there is no task vector (an empty string, never NULL, so the JPA
     * binding has a type). Aliases a, v, p, e.
     */
    public static final String ELIGIBLE_FROM = """
            FROM agents a
            JOIN agent_versions v ON v.id = a.current_version_id
            JOIN agent_profiles p ON p.agent_id = a.id
            LEFT JOIN agent_embeddings e ON e.agent_id = a.id AND e.model = :embeddingModel
            WHERE a.status = 'ACTIVE'
              AND p.is_listed
              AND v.capability_categories && ARRAY[:category]::text[]
              AND (CAST(:longestExecutionSeconds AS bigint) IS NULL
                   OR v.max_execution_seconds <= CAST(:longestExecutionSeconds AS bigint))
            """;

    /** The binding for {@code :taskVector} and {@code :embeddingModel} without a task vector. */
    public static final String NO_VECTOR = "";

    private MatchCandidateSql() {
    }
}
