package com.hireai.infrastructure.repository.task.item;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.domain.biz.acceptance.check.model.Finding;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.model.ItemCheck;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.domain.shared.model.Money;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** JDBC impl of {@link TaskItemRepository} over {@code task_items} (V42). Checks are stored as JSONB. */
@Repository
public class TaskItemRepositoryImpl implements TaskItemRepository {

    private static final String COLUMNS = "id, task_id, item_no, input, price, status, agent_status, "
            + "result_payload, result_url, checks::text AS checks, auto_accepted, source_snapshot_id, paid_amount, "
            + "findings::text AS findings, anomalies::text AS anomalies, rework_count";
    private static final TypeReference<List<ItemCheck>> CHECKS = new TypeReference<>() { };
    private static final TypeReference<List<Finding>> FINDINGS = new TypeReference<>() { };
    private static final TypeReference<List<SelfReportedAnomaly>> ANOMALIES = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskItemRepositoryImpl(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAll(TaskItems items) {
        MapSqlParameterSource[] rows = items.all().stream().map(this::params).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO task_items (id, task_id, item_no, input, price, status, agent_status,
                                        result_payload, result_url, checks, auto_accepted, source_snapshot_id)
                VALUES (:id, :taskId, :itemNo, :input, :price, :status, :agentStatus,
                        :resultPayload, :resultUrl, CAST(:checks AS jsonb), :autoAccepted, :sourceSnapshotId)
                """, rows);
    }

    @Override
    public void update(TaskItemModel item) {
        jdbc.update("""
                UPDATE task_items SET status = :status, agent_status = :agentStatus,
                       result_payload = :resultPayload, result_url = :resultUrl,
                       checks = CAST(:checks AS jsonb), auto_accepted = :autoAccepted,
                       paid_amount = :paidAmount, findings = CAST(:findings AS jsonb),
                       anomalies = CAST(:anomalies AS jsonb), rework_count = :reworks, updated_at = now()
                WHERE id = :id
                """, params(item));
    }

    @Override
    public void updateAll(List<TaskItemModel> items) {
        if (items.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                UPDATE task_items SET status = :status, agent_status = :agentStatus,
                       result_payload = :resultPayload, result_url = :resultUrl,
                       checks = CAST(:checks AS jsonb), auto_accepted = :autoAccepted,
                       paid_amount = :paidAmount, findings = CAST(:findings AS jsonb),
                       anomalies = CAST(:anomalies AS jsonb), rework_count = :reworks, updated_at = now()
                WHERE id = :id
                """, items.stream().map(this::params).toArray(MapSqlParameterSource[]::new));
    }

    @Override
    public TaskItems findByTaskId(UUID taskId) {
        return new TaskItems(jdbc.query("SELECT " + COLUMNS + " FROM task_items WHERE task_id = :taskId "
                + "ORDER BY item_no", Map.of("taskId", taskId), rowMapper()));
    }

    @Override
    public int countByTaskId(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_items WHERE task_id = :taskId",
                Map.of("taskId", taskId), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, TaskItems> findByTaskIds(Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<TaskItemModel> rows = jdbc.query("SELECT " + COLUMNS + " FROM task_items WHERE task_id IN (:ids) "
                + "ORDER BY task_id, item_no", Map.of("ids", taskIds), rowMapper());
        return rows.stream().collect(Collectors.groupingBy(TaskItemModel::taskId, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), TaskItems::new)));
    }

    @Override
    public Optional<TaskItemModel> findById(UUID itemId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_items WHERE id = :id", Map.of("id", itemId), rowMapper())
                .stream().findFirst();
    }

    @Override
    public List<TaskStatus> taskStatusesUsingSource(UUID sourceSnapshotId) {
        return jdbc.queryForList("SELECT DISTINCT t.status FROM task_items i JOIN tasks t ON t.id = i.task_id "
                        + "WHERE i.source_snapshot_id = :id", Map.of("id", sourceSnapshotId), String.class)
                .stream().map(TaskStatus::valueOf).toList();
    }

    private MapSqlParameterSource params(TaskItemModel item) {
        return new MapSqlParameterSource()
                .addValue("id", item.id())
                .addValue("taskId", item.taskId())
                .addValue("itemNo", item.itemNo())
                .addValue("input", item.input())
                .addValue("price", item.price().value())
                .addValue("status", item.status().name())
                .addValue("agentStatus", item.agentStatus())
                .addValue("resultPayload", item.resultPayload())
                .addValue("resultUrl", item.resultUrl())
                .addValue("checks", write(item.checks()))
                .addValue("findings", write(item.findings()))
                .addValue("anomalies", write(item.anomalies()))
                .addValue("autoAccepted", item.autoAccepted())
                .addValue("sourceSnapshotId", item.sourceSnapshotId())
                .addValue("paidAmount", item.paidAmount() == null ? null : item.paidAmount().value())
                .addValue("reworks", item.reworks());
    }

    private RowMapper<TaskItemModel> rowMapper() {
        return (rs, i) -> new TaskItemModel(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getInt("item_no"),
                rs.getString("input"), Money.of(rs.getBigDecimal("price")),
                ItemStatus.valueOf(rs.getString("status")), rs.getString("agent_status"),
                rs.getString("result_payload"), rs.getString("result_url"),
                read(rs.getString("checks"), CHECKS), rs.getBoolean("auto_accepted"),
                rs.getObject("source_snapshot_id", UUID.class),
                rs.getBigDecimal("paid_amount") == null ? null : Money.of(rs.getBigDecimal("paid_amount")),
                read(rs.getString("findings"), FINDINGS), read(rs.getString("anomalies"), ANOMALIES),
                rs.getInt("rework_count"));
    }

    private String write(List<?> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("serialize item checks/findings/anomalies", e);
        }
    }

    private <T> List<T> read(String json, TypeReference<List<T>> type) {
        try {
            return json == null ? List.of() : mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize item checks/findings/anomalies", e);
        }
    }

    @Override
    public List<FindingFacts.ItemRef> findEarlierItemsWithSameDocument(TaskItemModel item, UUID clientId) {
        if (item.sourceSnapshotId() == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT o.task_id, o.item_no
                FROM task_items me
                JOIN source_snapshots ms ON ms.id = me.source_snapshot_id
                JOIN tasks mt ON mt.id = me.task_id
                JOIN source_snapshots os ON os.sha256 = ms.sha256
                JOIN task_items o ON o.source_snapshot_id = os.id
                JOIN tasks ot ON ot.id = o.task_id
                WHERE me.id = :itemId AND ot.client_id = :clientId AND o.id <> me.id
                  AND ((o.task_id = me.task_id AND o.item_no < me.item_no)
                       OR (o.task_id <> me.task_id AND ot.gmt_create < mt.gmt_create))
                ORDER BY ot.gmt_create, o.item_no
                LIMIT 20
                """, Map.of("itemId", item.id(), "clientId", clientId),
                (rs, i) -> new FindingFacts.ItemRef(rs.getObject("task_id", UUID.class), rs.getInt("item_no")));
    }

    @Override
    public List<FindingFacts.ItemRef> findOtherItemsWithInvoiceKey(UUID clientId, String invoiceKey, UUID exceptItemId) {
        return jdbc.query("""
                SELECT task_id, item_no FROM item_invoice_keys
                WHERE client_id = :clientId AND invoice_key = :key AND item_id <> :itemId
                ORDER BY recorded_at, item_no
                LIMIT 20
                """, Map.of("clientId", clientId, "key", invoiceKey, "itemId", exceptItemId),
                (rs, i) -> new FindingFacts.ItemRef(rs.getObject("task_id", UUID.class), rs.getInt("item_no")));
    }

    @Override
    public void recordInvoiceKey(TaskItemModel item, UUID clientId, String invoiceKey) {
        if (invoiceKey == null) {
            jdbc.update("DELETE FROM item_invoice_keys WHERE item_id = :itemId", Map.of("itemId", item.id()));
            return;
        }
        jdbc.update("""
                INSERT INTO item_invoice_keys (item_id, task_id, item_no, client_id, invoice_key)
                VALUES (:itemId, :taskId, :itemNo, :clientId, :key)
                ON CONFLICT (item_id) DO UPDATE SET invoice_key = EXCLUDED.invoice_key, recorded_at = now()
                """, new MapSqlParameterSource().addValue("itemId", item.id()).addValue("taskId", item.taskId())
                .addValue("itemNo", item.itemNo()).addValue("clientId", clientId).addValue("key", invoiceKey));
    }
}
