package com.hireai.infrastructure.repository.offering.category;

import com.hireai.domain.biz.offering.category.model.CategorySuggestion;
import com.hireai.domain.biz.offering.category.repository.CategorySuggestionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class CategorySuggestionRepositoryImpl implements CategorySuggestionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CategorySuggestionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(CategorySuggestion s) {
        jdbc.update("""
                INSERT INTO category_suggestions
                    (id, agent_id, agent_version_id, owner_id, suggested_category, gmt_create)
                VALUES (:id, :agentId, :versionId, :ownerId, :text, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", s.id())
                .addValue("agentId", s.agentId())
                .addValue("versionId", s.agentVersionId())
                .addValue("ownerId", s.ownerId())
                .addValue("text", s.text())
                .addValue("createdAt", Timestamp.from(s.createdAt())));
    }

    @Override
    public List<CategorySuggestion> findByAgentId(UUID agentId) {
        return jdbc.query("""
                SELECT id, agent_id, agent_version_id, owner_id, suggested_category, gmt_create
                FROM category_suggestions WHERE agent_id = :agentId ORDER BY gmt_create, id
                """, Map.of("agentId", agentId),
                (rs, i) -> new CategorySuggestion(
                        rs.getObject("id", UUID.class), rs.getObject("agent_id", UUID.class),
                        rs.getObject("agent_version_id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getString("suggested_category"), rs.getTimestamp("gmt_create").toInstant()));
    }
}
