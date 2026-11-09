package com.hireai.infrastructure.repository.offering.embedding;

import com.hireai.domain.biz.offering.embedding.info.AgentEmbeddingSource;
import com.hireai.domain.biz.offering.embedding.model.AgentEmbeddingModel;
import com.hireai.domain.biz.offering.embedding.repository.AgentEmbeddingRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC over {@code agent_embeddings} (pgvector). The vector is bound as pgvector's text form
 * ({@code [x,y,...]}) and cast in SQL, so no pgvector Java type or Hibernate mapping is needed.
 * Agents without a storefront row (pre-V6) read with a null tagline and description.
 */
@Repository
public class AgentEmbeddingRepositoryImpl implements AgentEmbeddingRepository {

    private static final String SOURCE_SQL = """
            SELECT a.id AS agent_id, a.name, p.tagline, p.description, e.model, e.text_hash
            FROM agents a
            LEFT JOIN agent_profiles p ON p.agent_id = a.id
            LEFT JOIN agent_embeddings e ON e.agent_id = a.id
            """;

    private static final RowMapper<AgentEmbeddingSource> SOURCE_MAPPER = (rs, n) -> new AgentEmbeddingSource(
            rs.getObject("agent_id", UUID.class), rs.getString("name"), rs.getString("tagline"),
            rs.getString("description"), rs.getString("model"), rs.getString("text_hash"));

    private final NamedParameterJdbcTemplate jdbc;

    public AgentEmbeddingRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AgentEmbeddingSource> findSource(UUID agentId) {
        return jdbc.query(SOURCE_SQL + " WHERE a.id = :agentId",
                new MapSqlParameterSource("agentId", agentId), SOURCE_MAPPER).stream().findFirst();
    }

    @Override
    public List<AgentEmbeddingSource> findSourcesOfLiveAgents() {
        return jdbc.query(SOURCE_SQL + " WHERE a.status <> 'DEACTIVATED' ORDER BY a.gmt_create, a.id",
                SOURCE_MAPPER);
    }

    @Override
    public void save(AgentEmbeddingModel embedding) {
        jdbc.update("""
                INSERT INTO agent_embeddings (agent_id, model, text_hash, embedding, updated_at)
                VALUES (:agentId, :model, :textHash, CAST(:vector AS vector), :updatedAt)
                ON CONFLICT (agent_id) DO UPDATE
                   SET model = EXCLUDED.model,
                       text_hash = EXCLUDED.text_hash,
                       embedding = EXCLUDED.embedding,
                       updated_at = EXCLUDED.updated_at
                """, new MapSqlParameterSource()
                .addValue("agentId", embedding.agentId())
                .addValue("model", embedding.model())
                .addValue("textHash", embedding.textHash())
                .addValue("vector", toVectorLiteral(embedding.vector()))
                .addValue("updatedAt", Timestamp.from(embedding.updatedAt())));
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }
}
