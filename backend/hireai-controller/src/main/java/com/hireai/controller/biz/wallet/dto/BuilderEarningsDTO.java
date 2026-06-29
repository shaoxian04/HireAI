package com.hireai.controller.biz.wallet.dto;

import com.hireai.application.biz.ledger.wallet.BuilderEarningsReadAppService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outbound HTTP view of a builder's earnings. 1:1 with the app-service record. */
public record BuilderEarningsDTO(
        BigDecimal lifetimeEarned,
        BigDecimal pendingIfAccepted,
        int paidTaskCount,
        List<AgentEarningsDTO> perAgent,
        List<PayoutDTO> payouts
) {

    public record AgentEarningsDTO(UUID agentId, String agentName, BigDecimal earned,
                                   BigDecimal pendingIfAccepted, int paidTaskCount) {
    }

    public record PayoutDTO(UUID taskId, String taskTitle, String agentName,
                            BigDecimal amount, Instant settledAt) {
    }

    public static BuilderEarningsDTO from(BuilderEarningsReadAppService.Earnings e) {
        return new BuilderEarningsDTO(
                e.lifetimeEarned(),
                e.pendingIfAccepted(),
                e.paidTaskCount(),
                e.perAgent().stream().map(a -> new AgentEarningsDTO(
                        a.agentId(), a.agentName(), a.earned(), a.pendingIfAccepted(), a.paidTaskCount())).toList(),
                e.payouts().stream().map(p -> new PayoutDTO(
                        p.taskId(), p.taskTitle(), p.agentName(), p.amount(), p.settledAt())).toList());
    }
}
