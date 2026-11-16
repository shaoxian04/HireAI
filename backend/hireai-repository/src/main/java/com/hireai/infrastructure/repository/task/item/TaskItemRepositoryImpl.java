package com.hireai.infrastructure.repository.task.item;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            + "result_payload, result_url, checks::text AS checks, auto_accepted, source_snapshot_id, paid_amount";
    private static final TypeReference<List<ItemCheck>> CHECKS = new TypeReference<>() { };

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
                       paid_amount = :paidAmount, updated_at = now()
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
                       paid_amount = :paidAmount, updated_at = now()
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
                .addValue("checks", writeChecks(item.checks()))
                .addValue("autoAccepted", item.autoAccepted())
                .addValue("sourceSnapshotId", item.sourceSnapshotId())
                .addValue("paidAmount", item.paidAmount() == null ? null : item.paidAmount().value());
    }

    private RowMapper<TaskItemModel> rowMapper() {
        return (rs, i) -> new TaskItemModel(
                rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class), rs.getInt("item_no"),
                rs.getString("input"), Money.of(rs.getBigDecimal("price")),
                ItemStatus.valueOf(rs.getString("status")), rs.getString("agent_status"),
                rs.getString("result_payload"), rs.getString("result_url"),
                readChecks(rs.getString("checks")), rs.getBoolean("auto_accepted"),
                rs.getObject("source_snapshot_id", UUID.class),
                rs.getBigDecimal("paid_amount") == null ? null : Money.of(rs.getBigDecimal("paid_amount")));
    }

    private String writeChecks(List<ItemCheck> checks) {
        try {
            return mapper.writeValueAsString(checks);
        } catch (Exception e) {
            throw new IllegalStateException("serialize item checks", e);
        }
    }

    private List<ItemCheck> readChecks(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, CHECKS);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize item checks", e);
        }
    }
}
