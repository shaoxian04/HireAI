package com.hireai.infrastructure.repository.task.sampling;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;
import com.hireai.domain.biz.task.sampling.repository.ItemRejectionRepository;
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

/** JDBC impl of {@link ItemRejectionRepository} over item_rejections (V52). */
@Repository
public class ItemRejectionRepositoryImpl implements ItemRejectionRepository {

    /** The latest round of each Item: the only one the windows and notices act on. */
    private static final String LATEST = """
            SELECT r.* FROM item_rejections r
            WHERE r.round = (SELECT max(x.round) FROM item_rejections x WHERE x.item_id = r.item_id)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ItemRejectionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ItemRejectionModel rejection) {
        RejectionModel r = rejection.round();
        jdbc.update("""
                INSERT INTO item_rejections (id, task_id, item_id, item_no, round, reason_category, reason, rejected_at,
                    response_deadline, revision_allowed, response, response_reason, response_silent, responded_at,
                    decision_deadline, decision, decision_silent, decided_at)
                VALUES (:id, :taskId, :itemId, :itemNo, :round, :category, :reason, :rejectedAt, :responseDeadline,
                    :revisionAllowed, :response, :responseReason, :responseSilent, :respondedAt, :decisionDeadline,
                    :decision, :decisionSilent, :decidedAt)
                ON CONFLICT (id) DO UPDATE SET response = EXCLUDED.response,
                    response_reason = EXCLUDED.response_reason, response_silent = EXCLUDED.response_silent,
                    responded_at = EXCLUDED.responded_at, decision_deadline = EXCLUDED.decision_deadline,
                    decision = EXCLUDED.decision, decision_silent = EXCLUDED.decision_silent,
                    decided_at = EXCLUDED.decided_at
                """, new MapSqlParameterSource()
                .addValue("id", r.id())
                .addValue("taskId", r.taskId())
                .addValue("itemId", rejection.itemId())
                .addValue("itemNo", rejection.itemNo())
                .addValue("round", r.round())
                .addValue("category", r.reasonCategory().name())
                .addValue("reason", r.reason())
                .addValue("rejectedAt", ts(r.rejectedAt()))
                .addValue("responseDeadline", ts(r.responseDeadline()))
                .addValue("revisionAllowed", r.revisionAllowed())
                .addValue("response", r.response() == null ? null : r.response().name())
                .addValue("responseReason", r.responseReason())
                .addValue("responseSilent", r.responseSilent())
                .addValue("respondedAt", ts(r.respondedAt()))
                .addValue("decisionDeadline", ts(r.decisionDeadline()))
                .addValue("decision", r.decision() == null ? null : r.decision().name())
                .addValue("decisionSilent", r.decisionSilent())
                .addValue("decidedAt", ts(r.decidedAt())));
    }

    @Override
    public Optional<ItemRejectionModel> findLatest(UUID itemId) {
        return jdbc.query("SELECT * FROM item_rejections WHERE item_id = :itemId ORDER BY round DESC LIMIT 1",
                Map.of("itemId", itemId), rowMapper()).stream().findFirst();
    }

    @Override
    public List<ItemRejectionModel> findByTaskId(UUID taskId) {
        return jdbc.query("SELECT * FROM item_rejections WHERE task_id = :taskId ORDER BY item_no, round",
                Map.of("taskId", taskId), rowMapper());
    }

    @Override
    public List<UUID> findItemIdsResponseExpired(Instant now) {
        return ids(LATEST + " AND r.response IS NULL AND r.response_deadline <= :now", Map.of("now", ts(now)));
    }

    @Override
    public List<UUID> findItemIdsDecisionExpired(Instant now) {
        return ids(LATEST + " AND r.response = 'STAND_BY' AND r.decision IS NULL AND r.decision_deadline <= :now",
                Map.of("now", ts(now)));
    }

    @Override
    public List<UUID> findItemIdsNoticePending(Instant now, int maxAttempts) {
        return ids(LATEST + " AND r.response IS NULL AND r.notice_sent_at IS NULL"
                        + " AND r.notice_attempts < :max AND r.response_deadline > :now",
                Map.of("now", ts(now), "max", maxAttempts));
    }

    @Override
    public void recordNoticeAttempt(UUID roundId, Instant deliveredAt) {
        jdbc.update("UPDATE item_rejections SET notice_attempts = notice_attempts + 1, "
                        + "notice_sent_at = COALESCE(notice_sent_at, :at) WHERE id = :id",
                new MapSqlParameterSource().addValue("id", roundId).addValue("at", ts(deliveredAt)));
    }

    @Override
    public void recordReplacedResult(UUID roundId, String resultPayload, String resultUrl) {
        jdbc.update("UPDATE item_rejections SET replaced_result_payload = :payload, replaced_result_url = :url "
                        + "WHERE id = :id",
                new MapSqlParameterSource().addValue("id", roundId).addValue("payload", resultPayload)
                        .addValue("url", resultUrl));
    }

    private List<UUID> ids(String latestQuery, Map<String, ?> params) {
        return jdbc.queryForList("SELECT r.item_id FROM (" + latestQuery + ") r ORDER BY r.rejected_at",
                params, UUID.class);
    }

    private static RowMapper<ItemRejectionModel> rowMapper() {
        return (rs, n) -> new ItemRejectionModel(rs.getObject("item_id", UUID.class), rs.getInt("item_no"),
                new RejectionModel(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getInt("round"), RejectReason.valueOf(rs.getString("reason_category")),
                        rs.getString("reason"), instant(rs.getTimestamp("rejected_at")),
                        instant(rs.getTimestamp("response_deadline")), rs.getBoolean("revision_allowed"),
                        rs.getString("response") == null ? null : AgentResponse.valueOf(rs.getString("response")),
                        rs.getString("response_reason"), rs.getBoolean("response_silent"),
                        instant(rs.getTimestamp("responded_at")), instant(rs.getTimestamp("decision_deadline")),
                        rs.getString("decision") == null ? null : ClientDecision.valueOf(rs.getString("decision")),
                        rs.getBoolean("decision_silent"), instant(rs.getTimestamp("decided_at"))));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
