package com.hireai.infrastructure.repository.acceptance.template;

import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.domain.biz.acceptance.template.model.TemplateModel;
import com.hireai.domain.biz.acceptance.template.model.TemplateRef;
import com.hireai.domain.biz.acceptance.template.repository.TemplateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code contract_templates} (V39) with plain JDBC: the table is Flyway-owned and read-only
 * to the app, so it has no JPA entity. The definition JSONB is mapped by {@link AcceptanceJsonMapper}.
 */
@Repository
public class TemplateRepositoryImpl implements TemplateRepository {

    private static final String SELECT =
            "SELECT template_key, version, status, published_at, definition::text AS definition FROM contract_templates";

    private final JdbcTemplate jdbc;
    private final AcceptanceJsonMapper mapper;

    public TemplateRepositoryImpl(JdbcTemplate jdbc, AcceptanceJsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<TemplateModel> find(TemplateRef ref) {
        return jdbc.query(SELECT + " WHERE template_key = ? AND version = ?", rowMapper(), ref.key(), ref.version())
                .stream().findFirst();
    }

    @Override
    public List<TemplateModel> findAll() {
        return jdbc.query(SELECT + " ORDER BY template_key, version", rowMapper());
    }

    @Override
    public List<TemplateModel> findVersions(String key) {
        return jdbc.query(SELECT + " WHERE template_key = ? ORDER BY version", rowMapper(), key);
    }

    private RowMapper<TemplateModel> rowMapper() {
        return (rs, rowNum) -> {
            Timestamp publishedAt = rs.getTimestamp("published_at");
            return mapper.templateFromRow(rs.getString("template_key"), rs.getInt("version"),
                    rs.getString("status"), publishedAt == null ? null : publishedAt.toInstant(),
                    rs.getString("definition"));
        };
    }
}
