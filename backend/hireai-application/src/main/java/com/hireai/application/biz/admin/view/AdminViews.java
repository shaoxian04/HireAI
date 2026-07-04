package com.hireai.application.biz.admin.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only projections the admin surface renders. Returned straight to the controller as JSON. */
public final class AdminViews {

    private AdminViews() {}

    public record Overview(long disputesOpen, long disputesArbitrating, long disputesEscalated,
                           long disputesResolved, long tasksTotal, long usersTotal, long agentsTotal,
                           BigDecimal escrowHeld, BigDecimal commissionEarned) {}

    public record DisputeRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String reasonCategory, Instant createdAt, String clientName,
                             boolean hasArbitratorRuling, boolean needsAttention) {}

    /** Full evidence a DAO assembles for the dispute detail (task submission + agent + result). */
    public record Evidence(UUID taskId, String taskTitle, String taskDescription, String clientName,
                           String clientReason, BigDecimal budget, String category, String outputFormat,
                           Instant submittedAt, Instant resultReceivedAt,
                           String agentName, String builderName, BigDecimal agentReputation, BigDecimal agentPrice,
                           String outputSpecJson, String resultPayloadJson, String resultUrl, String agentStatus) {}

    public record TaskRow(UUID id, String title, String status, BigDecimal budget, String clientName,
                          Instant createdAt) {}

    public record UserRow(UUID id, String name, String email, List<String> roles,
                          BigDecimal availableBalance, BigDecimal escrowBalance) {}

    public record AgentRow(UUID id, String name, String status, String builderName,
                           BigDecimal reputationScore, BigDecimal price) {}

    public record RulingView(int tier, String decidedBy, String category, String rationale,
                             Instant decidedAt) {}

    /**
     * What each ruling category would settle for this budget, computed server-side from SettlementPolicy
     * (so the money math is never duplicated in the UI). Amounts are indicative — the actual movement
     * still happens deterministically in the domain at apply-time (Inv #3).
     */
    public record SettlementPreview(BigDecimal budget, BigDecimal fulfilledPayout, BigDecimal fulfilledCommission,
                                    BigDecimal notFulfilledRefund, BigDecimal partialBuilderNet,
                                    BigDecimal partialClientRefund) {}

    public record DisputeDetail(UUID disputeId, UUID taskId, String taskTitle, String taskDescription,
                                String status, String reasonCategory, String clientReason,
                                Instant createdAt, String clientName,
                                BigDecimal budget, String category, String outputFormat,
                                Instant submittedAt, Instant resultReceivedAt,
                                String agentName, String builderName, BigDecimal agentReputation, BigDecimal agentPrice,
                                String outputSpecJson, String resultPayloadJson, String resultUrl, String agentStatus,
                                boolean actionable, SettlementPreview settlementPreview, List<RulingView> rulings) {}
}
