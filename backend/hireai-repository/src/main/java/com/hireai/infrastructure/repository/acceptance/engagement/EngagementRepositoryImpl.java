package com.hireai.infrastructure.repository.acceptance.engagement;

import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.domain.biz.acceptance.engagement.enums.EngagementStatus;
import com.hireai.domain.biz.acceptance.engagement.model.EngagementModel;
import com.hireai.domain.biz.acceptance.engagement.repository.EngagementRepository;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@code engagements} (V48) with plain JDBC. The table's trigger freezes the parties, consent and variant. */
@Repository
public class EngagementRepositoryImpl implements EngagementRepository {

    private static final String COLUMNS = "id, client_id, agent_id, status, consented_at, "
            + "template_variant::text AS template_variant, agent_accepted_at, retention_days, created_at, ended_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final AcceptanceJsonMapper json;

    public EngagementRepositoryImpl(NamedParameterJdbcTemplate jdbc, AcceptanceJsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void insert(EngagementModel e) {
        try {
            jdbc.update("""
                    INSERT INTO engagements (id, client_id, agent_id, status, consented_at, template_variant,
                                             agent_accepted_at, retention_days, created_at, ended_at)
                    VALUES (:id, :clientId, :agentId, :status, :consentedAt, CAST(:variant AS jsonb),
                            :acceptedAt, :retentionDays, :createdAt, :endedAt)
                    """, params(e));
        } catch (DuplicateKeyException ex) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "You already have an open Engagement with this Agent; end it before opening another");
        }
    }

    @Override
    public void update(EngagementModel e) {
        jdbc.update("UPDATE engagements SET status = :status, agent_accepted_at = :acceptedAt, ended_at = :endedAt "
                + "WHERE id = :id", params(e));
    }

    @Override
    public Optional<EngagementModel> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM engagements WHERE id = :id", Map.of("id", id), row())
                .stream().findFirst();
    }

    @Override
    public List<EngagementModel> findByClientId(UUID clientId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM engagements WHERE client_id = :clientId "
                + "ORDER BY created_at DESC", Map.of("clientId", clientId), row());
    }

    @Override
    public List<EngagementModel> findByAgentIds(Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM engagements WHERE agent_id IN (:ids) "
                + "ORDER BY created_at DESC", Map.of("ids", agentIds), row());
    }

    private MapSqlParameterSource params(EngagementModel e) {
        return new MapSqlParameterSource()
                .addValue("id", e.id())
                .addValue("clientId", e.clientId())
                .addValue("agentId", e.agentId())
                .addValue("status", e.status().name())
                .addValue("consentedAt", ts(e.consentedAt()))
                .addValue("variant", e.templateVariant() == null ? null : json.toJson(e.templateVariant()))
                .addValue("acceptedAt", ts(e.agentAcceptedAt()))
                .addValue("retentionDays", e.retentionDays())
                .addValue("createdAt", ts(e.createdAt()))
                .addValue("endedAt", ts(e.endedAt()));
    }

    private RowMapper<EngagementModel> row() {
        return (rs, n) -> {
            String variant = rs.getString("template_variant");
            return new EngagementModel(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class),
                    rs.getObject("agent_id", UUID.class), EngagementStatus.valueOf(rs.getString("status")),
                    instant(rs.getTimestamp("consented_at")), variant == null ? null : json.contractFromJson(variant),
                    instant(rs.getTimestamp("agent_accepted_at")), rs.getInt("retention_days"),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("ended_at")));
        };
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
