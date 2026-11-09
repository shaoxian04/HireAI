package com.hireai.infrastructure.repository.offering.embedding;

import com.hireai.domain.biz.offering.embedding.info.TextVector;
import com.hireai.domain.biz.offering.embedding.repository.TextEmbeddingRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** JDBC over {@code text_embeddings} (pgvector), the vector read and written in its text form. */
@Repository
public class TextEmbeddingRepositoryImpl implements TextEmbeddingRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TextEmbeddingRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TextVector> find(String textHash, String model) {
        return jdbc.query("""
                        SELECT embedding::text AS embedding FROM text_embeddings
                        WHERE text_hash = :textHash AND model = :model
                        """,
                new MapSqlParameterSource().addValue("textHash", textHash).addValue("model", model),
                (rs, n) -> new TextVector(model, PgVectors.parse(rs.getString("embedding"))))
                .stream().findFirst();
    }

    @Override
    public void saveIfAbsent(String textHash, TextVector vector, Instant now) {
        jdbc.update("""
                INSERT INTO text_embeddings (text_hash, model, embedding, gmt_create)
                VALUES (:textHash, :model, CAST(:vector AS vector), :now)
                ON CONFLICT (text_hash, model) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("textHash", textHash)
                .addValue("model", vector.model())
                .addValue("vector", PgVectors.toLiteral(vector.vector()))
                .addValue("now", Timestamp.from(now)));
    }
}
