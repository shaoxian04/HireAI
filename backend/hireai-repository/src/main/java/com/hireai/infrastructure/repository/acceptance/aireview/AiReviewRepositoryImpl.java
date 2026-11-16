package com.hireai.infrastructure.repository.acceptance.aireview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.acceptance.aireview.enums.AiReviewStatus;
import com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel;
import com.hireai.domain.biz.acceptance.aireview.model.CriterionVerdict;
import com.hireai.domain.biz.acceptance.aireview.repository.AiReviewRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC impl of {@link AiReviewRepository} over {@code ai_reviews} (V49). The criteria are not stored
 * here: they are the frozen Contract's, read from {@code tasks.contract -> 'aiReview'}.
 */
@Repository
public class AiReviewRepositoryImpl implements AiReviewRepository {

    private static final String SELECT = """
            SELECT r.task_id, r.fee, r.status, r.decisive, r.attempts, r.verdicts::text AS verdicts, r.model,
                   r.failure_reason, r.created_at, r.requested_at, r.completed_at,
                   (t.contract -> 'aiReview' -> 'criteria')::text AS criteria
            FROM ai_reviews r JOIN tasks t ON t.id = r.task_id
            WHERE r.task_id = :taskId
            """;
    private static final TypeReference<List<VerdictRow>> VERDICTS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiReviewRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AiReviewModel review) {
        jdbc.update("""
                INSERT INTO ai_reviews (task_id, fee, status, decisive, attempts, verdicts, ruling_category, model,
                                        failure_reason, created_at, requested_at, completed_at)
                VALUES (:taskId, :fee, :status, :decisive, :attempts, CAST(:verdicts AS jsonb), :ruling, :model,
                        :failureReason, :createdAt, :requestedAt, :completedAt)
                """, params(review));
    }

    @Override
    public void save(AiReviewModel review) {
        jdbc.update("""
                UPDATE ai_reviews SET status = :status, decisive = :decisive, attempts = :attempts,
                       verdicts = CAST(:verdicts AS jsonb), ruling_category = :ruling, model = :model,
                       failure_reason = :failureReason, requested_at = :requestedAt, completed_at = :completedAt
                WHERE task_id = :taskId
                """, params(review));
    }

    @Override
    public Optional<AiReviewModel> findByTaskId(UUID taskId) {
        return jdbc.query(SELECT, Map.of("taskId", taskId), rowMapper()).stream().findFirst();
    }

    @Override
    public Optional<AiReviewModel> lockByTaskId(UUID taskId) {
        return jdbc.query(SELECT + " FOR UPDATE OF r", Map.of("taskId", taskId), rowMapper()).stream().findFirst();
    }

    @Override
    public List<UUID> findRequestedBefore(Instant cutoff) {
        return jdbc.queryForList("SELECT task_id FROM ai_reviews WHERE status = 'REQUESTED' "
                        + "AND requested_at < :cutoff ORDER BY requested_at LIMIT 100",
                Map.of("cutoff", Timestamp.from(cutoff)), UUID.class);
    }

    @Override
    public List<UUID> findFrozenOnEndedTasks() {
        return jdbc.queryForList("""
                SELECT r.task_id FROM ai_reviews r JOIN tasks t ON t.id = r.task_id
                WHERE r.status = 'FROZEN'
                  AND t.status IN ('RESOLVED', 'TIMED_OUT', 'SPEC_VIOLATION', 'FAILED', 'CANCELLED')
                ORDER BY r.created_at LIMIT 100
                """, Map.of(), UUID.class);
    }

    private MapSqlParameterSource params(AiReviewModel r) {
        return new MapSqlParameterSource()
                .addValue("taskId", r.taskId())
                .addValue("fee", r.fee().value())
                .addValue("status", r.status().name())
                .addValue("decisive", r.decisive())
                .addValue("attempts", r.attempts())
                .addValue("verdicts", r.status() == AiReviewStatus.COMPLETED ? write(r.verdicts().stream()
                        .map(v -> new VerdictRow(v.criterion(), v.passed(), v.reason())).toList()) : null)
                .addValue("ruling", r.status() == AiReviewStatus.COMPLETED ? r.rulingCategory().name() : null)
                .addValue("model", r.model())
                .addValue("failureReason", r.failureReason() == null || r.failureReason().length() <= 1000
                        ? r.failureReason() : r.failureReason().substring(0, 999) + "…")
                .addValue("createdAt", ts(r.createdAt()))
                .addValue("requestedAt", ts(r.requestedAt()))
                .addValue("completedAt", ts(r.completedAt()));
    }

    private RowMapper<AiReviewModel> rowMapper() {
        return (rs, i) -> {
            String verdicts = rs.getString("verdicts");
            String criteria = rs.getString("criteria");
            return new AiReviewModel(rs.getObject("task_id", UUID.class),
                    criteria == null ? List.of() : read(criteria, STRINGS),
                    Money.of(rs.getBigDecimal("fee")), AiReviewStatus.valueOf(rs.getString("status")),
                    rs.getBoolean("decisive"), rs.getInt("attempts"),
                    verdicts == null ? List.of() : read(verdicts, VERDICTS).stream()
                            .map(v -> new CriterionVerdict(v.criterion(), v.passed(), v.reason())).toList(),
                    rs.getString("model"), rs.getString("failure_reason"),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("requested_at")),
                    instant(rs.getTimestamp("completed_at")));
        };
    }

    /** The stored shape of one verdict. */
    record VerdictRow(String criterion, boolean passed, String reason) {
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise AI Review verdicts", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable AI Review row", e);
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
