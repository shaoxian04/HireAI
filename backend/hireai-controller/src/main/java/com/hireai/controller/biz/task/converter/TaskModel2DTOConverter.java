package com.hireai.controller.biz.task.converter;

import com.hireai.controller.biz.task.dto.TaskDTO;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.ledger.wallet.service.SettlementPolicy;

import java.math.BigDecimal;

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
        OutputSpec spec = task.outputSpec();
        BigDecimal payout = null;
        BigDecimal commission = null;
        BigDecimal refund = null;
        if (task.resolution() == TaskResolution.ACCEPTED) {
            payout = SettlementPolicy.netOf(task.budget()).value();
            commission = SettlementPolicy.commissionOn(task.budget()).value();
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
                task.createdAt(),
                task.resolution() == null ? null : task.resolution().name(),
                task.resolvedAt(),
                task.rejectionReason(),
                payout,
                commission,
                refund);
    }
}
