package com.hireai.application.biz.admin.impl;

import com.hireai.application.biz.admin.AdminQueryPort;
import com.hireai.application.biz.admin.AdminReadAppService;
import com.hireai.application.biz.admin.view.AdminViews;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.ledger.settlement.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AdminReadAppServiceImpl implements AdminReadAppService {

    private static final int RECENT_TASK_LIMIT = 50;

    private final AdminQueryPort adminQueryPort;
    private final DisputeRepository disputeRepository;

    public AdminReadAppServiceImpl(AdminQueryPort adminQueryPort, DisputeRepository disputeRepository) {
        this.adminQueryPort = adminQueryPort;
        this.disputeRepository = disputeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AdminViews.Overview overview() {
        return adminQueryPort.overview();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.DisputeRow> disputeQueue(boolean needsAttentionOnly) {
        return adminQueryPort.disputeQueue(needsAttentionOnly);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminViews.DisputeDetail disputeDetail(UUID disputeId) {
        DisputeModel dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Dispute not found: " + disputeId));
        AdminViews.Evidence ev = adminQueryPort.disputeEvidence(dispute.taskId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Task not found for dispute: " + disputeId));
        List<AdminViews.RulingView> rulings = dispute.rulings().stream()
                .map(r -> new AdminViews.RulingView(r.tier(), r.decidedBy().name(), r.category().name(),
                        r.rationale(), r.decidedAt()))
                .toList();
        boolean actionable = switch (dispute.status()) {
            case OPEN, ARBITRATING, ESCALATED -> true;
            case RULED, RESOLVED -> false;
        };
        AdminViews.SettlementPreview preview = settlementPreview(ev.budget());
        return new AdminViews.DisputeDetail(disputeId, dispute.taskId(), ev.taskTitle(), ev.taskDescription(),
                dispute.status().name(), dispute.reasonCategory().name(), ev.clientReason(),
                dispute.createdAt(), ev.clientName(),
                ev.budget(), ev.category(), ev.outputFormat(), ev.submittedAt(), ev.resultReceivedAt(),
                ev.agentName(), ev.builderName(), ev.agentReputation(), ev.agentPrice(),
                ev.outputSpecJson(), ev.resultPayloadJson(), ev.resultUrl(), ev.agentStatus(),
                actionable, preview, rulings);
    }

    /** What each ruling category would move for this budget (authoritative math via SettlementPolicy). */
    private AdminViews.SettlementPreview settlementPreview(BigDecimal budgetValue) {
        Money budget = Money.of(budgetValue);
        Money splitBuilderGross = SettlementPolicy.builderShareOnSplit(budget);
        return new AdminViews.SettlementPreview(
                budget.value(),
                SettlementPolicy.netOf(budget).value(),
                SettlementPolicy.commissionOn(budget).value(),
                budget.value(),
                SettlementPolicy.netOf(splitBuilderGross).value(),
                budget.subtract(splitBuilderGross).value());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.TaskRow> recentTasks() {
        return adminQueryPort.recentTasks(RECENT_TASK_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.UserRow> users() {
        return adminQueryPort.usersWithWallets();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminViews.AgentRow> agents() {
        return adminQueryPort.agents();
    }
}
