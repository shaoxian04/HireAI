package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.task.dto.RejectionDTO;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.biz.task.dto.TaskItemDTO;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.TaskSettlementRecord;
import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Explicit, hand-written converter from the Task domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 * Settlement amounts are read from the recorded settlements ({@link TaskSettlementRecord}), so what a
 * client or builder is shown is exactly what was settled.
 */
public final class TaskModel2DTOConverter {

    private TaskModel2DTOConverter() {
    }

    /** A task without its settlement amounts (none recorded, or not needed by the caller). */
    public static TaskDTO toDTO(TaskModel task) {
        return toDTO(task, (TaskSettlementRecord) null);
    }

    /** {@code settled}: the task's recorded settlements, where every amount shown comes from (nullable). */
    public static TaskDTO toDTO(TaskModel task, TaskSettlementRecord settled) {
        return toDTO(task, null, null, null, null, null, false, settled);
    }

    /**
     * The full view. {@code agentId}, the review window (#94), the latest rejection round (#95) and the failure
     * detail (#91) are resolved by the caller (each nullable); {@code items} are a multi-Item Task's Items
     * (spec S3; null or empty otherwise), listed when {@code listItems}. The amounts come only from
     * {@code settled}, the recorded settlements, never from re-deriving the policy here.
     */
    public static TaskDTO toDTO(TaskModel task, UUID agentId, ReviewWindowState reviewWindow,
                                RejectionModel rejection, String failureDetail, TaskItems items,
                                boolean listItems, TaskSettlementRecord settled) {
        OutputSpec spec = task.outputDescription();
        boolean multiItem = items != null && !items.isEmpty();
        BigDecimal payout = settled == null ? null : positiveOrNull(settled.net());
        BigDecimal commission = settled == null ? null : positiveOrNull(settled.commission());
        BigDecimal refund = settled == null ? null : positiveOrNull(settled.refund());
        return new TaskDTO(
                task.id(),
                task.clientId(),
                task.title(),
                task.description(),
                task.budget().value(),
                task.status().name(),
                new TaskDTO.OutputSpecDTO(spec.format().name(), spec.schema(), spec.acceptanceCriteria()),
                Acceptance2DTOConverter.toDTO(task.contract()),
                task.createdAt(),
                task.resolution() == null ? null : task.resolution().name(),
                task.resolvedAt(),
                task.rejectionReason(),
                payout,
                commission,
                refund,
                task.failureReason() == null ? null : task.failureReason().name(),
                task.category(),
                agentId,
                reviewWindow == null ? null : reviewWindow.deadline(),
                reviewWindow != null && reviewWindow.autoAccepted() ? Boolean.TRUE : null,
                RejectionDTO.from(rejection),
                failureDetail,
                multiItem ? items.count() : null,
                multiItem ? items.all().get(0).price().value() : null,
                multiItem && listItems ? items.all().stream().map(i -> toItemDTO(i, settled)).toList() : null,
                null, null, null);
    }

    private static BigDecimal positiveOrNull(Money amount) {
        return amount.isPositive() ? amount.value() : null;
    }

    private static TaskItemDTO toItemDTO(TaskItemModel item, TaskSettlementRecord settled) {
        ItemSettlementModel s = settled == null ? null : settled.item(item.id()).orElse(null);
        BigDecimal paid = s == null || !s.net().add(s.commission()).isPositive() ? null : s.net().value();
        BigDecimal cut = s == null || paid == null ? null : s.commission().value();
        BigDecimal refund = s == null ? null : positiveOrNull(s.refund());
        return new TaskItemDTO(item.itemNo(), item.input(), item.status().name(), item.price().value(),
                item.resultPayload(), item.resultUrl(),
                item.checks().isEmpty() ? null : item.checks().stream()
                        .map(c -> new TaskItemDTO.CheckDTO(c.rule(), c.passed(), c.detail(), c.outcome().name()))
                        .toList(),
                item.autoAccepted() ? Boolean.TRUE : null,
                paid,
                cut,
                refund,
                item.sourceSnapshotId(),
                item.needsReview() ? Boolean.TRUE : null,
                item.findings().isEmpty() ? null : item.findings().stream()
                        .map(f -> new com.hireai.controller.biz.adjudication.dto.ValidationReportDTO.FindingDTO(f.code(), f.detail())).toList(),
                item.anomalies().isEmpty() ? null : item.anomalies().stream()
                        .map(a -> new com.hireai.controller.biz.adjudication.dto.ValidationReportDTO.AnomalyDTO(a.check(), a.description())).toList());
    }
}
