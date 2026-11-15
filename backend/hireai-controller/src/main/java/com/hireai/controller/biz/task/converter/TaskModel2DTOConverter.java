package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.acceptance.converter.Acceptance2DTOConverter;
import com.hireai.controller.biz.task.dto.RejectionDTO;
import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.controller.biz.task.dto.TaskItemDTO;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Explicit, hand-written converter from the Task domain model to its outbound DTO.
 * One direction only; no auto-mapping, so what crosses the boundary is deliberate.
 * Settlement display amounts are derived from SettlementPolicy here so the commission
 * rate lives in exactly one place; the ledger remains the record of truth.
 */
public final class TaskModel2DTOConverter {

    private TaskModel2DTOConverter() {
    }

    public static TaskDTO toDTO(TaskModel task) {
        return toDTO(task, null);
    }

    /** {@code agentId} is the Agent the task was booked with, resolved by the caller (nullable). */
    public static TaskDTO toDTO(TaskModel task, UUID agentId) {
        return toDTO(task, agentId, null);
    }

    /** Also carries the task's review window (#94), resolved by the caller (nullable). */
    public static TaskDTO toDTO(TaskModel task, UUID agentId, ReviewWindowState reviewWindow) {
        return toDTO(task, agentId, reviewWindow, null);
    }

    /** Also carries the task's latest rejection round (#95), resolved by the caller (nullable). */
    public static TaskDTO toDTO(TaskModel task, UUID agentId, ReviewWindowState reviewWindow,
                                RejectionModel rejection) {
        return toDTO(task, agentId, reviewWindow, rejection, null, false);
    }

    /**
     * Also carries a multi-Item Task's Items (spec S3; null or empty for a single-deliverable Task).
     * {@code listItems} false keeps the Item list out (list views) while the amounts still come
     * from the Items, since each Item settled on its own.
     */
    public static TaskDTO toDTO(TaskModel task, UUID agentId, ReviewWindowState reviewWindow,
                                RejectionModel rejection, TaskItems items, boolean listItems) {
        OutputSpec spec = task.outputSpec();
        BigDecimal payout = null;
        BigDecimal commission = null;
        BigDecimal refund = null;
        boolean multiItem = items != null && !items.isEmpty();
        if (multiItem) {
            Money net = Money.ZERO;
            Money cut = Money.ZERO;
            Money back = Money.ZERO;
            for (TaskItemModel item : items.all()) {
                if (item.status() == ItemStatus.ACCEPTED) {
                    net = net.add(SettlementPolicy.netOf(item.price()));
                    cut = cut.add(SettlementPolicy.commissionOn(item.price()));
                } else if (item.isSettled()) {
                    back = back.add(item.price());
                }
            }
            payout = net.isPositive() ? net.value() : null;
            commission = cut.isPositive() ? cut.value() : null;
            refund = back.isPositive() ? back.value() : null;
        } else if (task.resolution() == TaskResolution.ACCEPTED) {
            payout = SettlementPolicy.netOf(task.budget()).value();
            commission = SettlementPolicy.commissionOn(task.budget()).value();
        } else if (task.resolution() == TaskResolution.PARTIALLY_ACCEPTED) {
            Money builderShare = SettlementPolicy.builderShareOnSplit(task.budget());        // 50.00 of 100
            Money splitCommission = SettlementPolicy.commissionOn(builderShare);              // 7.50
            payout = builderShare.subtract(splitCommission).value();                         // 42.50
            commission = splitCommission.value();                                            // 7.50
            refund = task.budget().subtract(builderShare).value();                           // 50.00
        } else if (task.resolution() == TaskResolution.REJECTED) {
            refund = task.budget().value();
        }
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
                multiItem ? items.count() : null,
                multiItem ? items.all().get(0).price().value() : null,
                multiItem && listItems ? items.all().stream().map(TaskModel2DTOConverter::toItemDTO).toList() : null);
    }

    private static TaskItemDTO toItemDTO(TaskItemModel item) {
        boolean accepted = item.status() == ItemStatus.ACCEPTED;
        boolean refunded = item.isSettled() && !accepted;
        return new TaskItemDTO(item.itemNo(), item.input(), item.status().name(), item.price().value(),
                item.resultPayload(), item.resultUrl(),
                item.checks().isEmpty() ? null : item.checks().stream()
                        .map(c -> new TaskItemDTO.CheckDTO(c.rule(), c.passed(), c.detail()))
                        .toList(),
                item.autoAccepted() ? Boolean.TRUE : null,
                accepted ? SettlementPolicy.netOf(item.price()).value() : null,
                accepted ? SettlementPolicy.commissionOn(item.price()).value() : null,
                refunded ? item.price().value() : null);
    }
}
