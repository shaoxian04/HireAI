package com.hireai.infrastructure.repository.task.sampling;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.enums.InspectionStatus;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;
import com.hireai.domain.biz.task.sampling.model.SamplingDecision;
import com.hireai.domain.biz.task.sampling.model.SamplingPlan;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** JDBC impl of {@link InspectionRepository} over task_inspections, sample_draws and item_judgements (V52). */
@Repository
public class InspectionRepositoryImpl implements InspectionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InspectionRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(InspectionModel i) {
        jdbc.update("""
                INSERT INTO task_inspections (task_id, inspection_level, round, status, error_rate)
                VALUES (:taskId, :level, :round, :status, :errorRate)
                ON CONFLICT (task_id) DO UPDATE SET inspection_level = EXCLUDED.inspection_level,
                    round = EXCLUDED.round, status = EXCLUDED.status, error_rate = EXCLUDED.error_rate,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("taskId", i.taskId())
                .addValue("level", i.level().name())
                .addValue("round", i.round())
                .addValue("status", i.status().name())
                .addValue("errorRate", i.errorRate()));
    }

    @Override
    public Optional<InspectionModel> findByTaskId(UUID taskId) {
        return jdbc.query("SELECT task_id, inspection_level, round, status, error_rate FROM task_inspections "
                        + "WHERE task_id = :taskId", Map.of("taskId", taskId),
                (rs, n) -> new InspectionModel(rs.getObject("task_id", UUID.class),
                        InspectionLevel.valueOf(rs.getString("inspection_level")), rs.getInt("round"),
                        InspectionStatus.valueOf(rs.getString("status")), rs.getBigDecimal("error_rate")))
                .stream().findFirst();
    }

    @Override
    public void insertDraw(SampleDrawModel d) {
        SamplingPlan p = d.plan();
        jdbc.update("""
                INSERT INTO sample_draws (id, task_id, round, stage, seed, code_letter, lot_size, first_sample_size,
                    second_sample_size, first_accept, first_reject, second_accept, second_reject, full_inspection,
                    item_nos, drawn_at)
                VALUES (:id, :taskId, :round, :stage, :seed, :letter, :lot, :n1, :n2, :ac1, :re1, :ac2, :re2, :full,
                    CAST(:itemNos AS INT[]), :drawnAt)
                """, new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("taskId", d.taskId())
                .addValue("round", d.round())
                .addValue("stage", d.stage())
                .addValue("seed", d.seed())
                .addValue("letter", String.valueOf(p.codeLetter()))
                .addValue("lot", p.lotSize())
                .addValue("n1", p.firstSampleSize())
                .addValue("n2", p.secondSampleSize())
                .addValue("ac1", p.firstAccept())
                .addValue("re1", p.firstReject())
                .addValue("ac2", p.secondAccept())
                .addValue("re2", p.secondReject())
                .addValue("full", p.fullInspection())
                .addValue("itemNos", toArrayLiteral(d.itemNos()))
                .addValue("drawnAt", Timestamp.from(d.drawnAt())));
    }

    @Override
    public void recordDecision(SampleDrawModel d) {
        jdbc.update("UPDATE sample_draws SET defects = :defects, decision = :decision, decided_at = :at "
                        + "WHERE id = :id AND decision IS NULL",
                new MapSqlParameterSource()
                        .addValue("id", d.id())
                        .addValue("defects", d.defects())
                        .addValue("decision", d.decision().name())
                        .addValue("at", Timestamp.from(d.decidedAt())));
    }

    @Override
    public List<SampleDrawModel> findDraws(UUID taskId) {
        return jdbc.query("SELECT * FROM sample_draws WHERE task_id = :taskId ORDER BY round, stage",
                Map.of("taskId", taskId), (rs, n) -> new SampleDrawModel(
                        rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getInt("round"), rs.getInt("stage"), rs.getLong("seed"),
                        new SamplingPlan(rs.getString("code_letter").charAt(0), rs.getInt("lot_size"),
                                rs.getInt("first_sample_size"), rs.getInt("second_sample_size"),
                                rs.getInt("first_accept"), rs.getInt("first_reject"),
                                rs.getInt("second_accept"), rs.getInt("second_reject"),
                                rs.getBoolean("full_inspection")),
                        readArray(rs.getArray("item_nos")),
                        rs.getTimestamp("drawn_at").toInstant(),
                        (Integer) rs.getObject("defects"),
                        rs.getString("decision") == null ? null : SamplingDecision.Next.valueOf(rs.getString("decision")),
                        rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()));
    }

    @Override
    public void appendJudgement(ItemJudgementModel j) {
        jdbc.update("""
                INSERT INTO item_judgements (id, task_id, item_id, item_no, round, passed, reason_category, reason,
                    sampled, judged_by, judged_at)
                VALUES (:id, :taskId, :itemId, :itemNo, :round, :passed, :category, :reason, :sampled, :by, :at)
                """, new MapSqlParameterSource()
                .addValue("id", j.id())
                .addValue("taskId", j.taskId())
                .addValue("itemId", j.itemId())
                .addValue("itemNo", j.itemNo())
                .addValue("round", j.round())
                .addValue("passed", j.passed())
                .addValue("category", j.reasonCategory() == null ? null : j.reasonCategory().name())
                .addValue("reason", j.reason())
                .addValue("sampled", j.sampled())
                .addValue("by", j.judgedBy())
                .addValue("at", Timestamp.from(j.judgedAt())));
    }

    @Override
    public List<ItemJudgementModel> findJudgements(UUID taskId) {
        return jdbc.query("SELECT * FROM item_judgements WHERE task_id = :taskId ORDER BY judged_at, id",
                Map.of("taskId", taskId), (rs, n) -> new ItemJudgementModel(
                        rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getObject("item_id", UUID.class), rs.getInt("item_no"), rs.getInt("round"),
                        rs.getBoolean("passed"),
                        rs.getString("reason_category") == null ? null : RejectReason.valueOf(rs.getString("reason_category")),
                        rs.getString("reason"), rs.getBoolean("sampled"), rs.getObject("judged_by", UUID.class),
                        rs.getTimestamp("judged_at").toInstant()));
    }

    private static String toArrayLiteral(List<Integer> values) {
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }

    private static List<Integer> readArray(Array array) throws SQLException {
        Object raw = array.getArray();
        if (raw instanceof Integer[] ints) {
            return List.copyOf(Arrays.asList(ints));
        }
        return Arrays.stream((Object[]) raw).map(o -> ((Number) o).intValue()).toList();
    }
}
