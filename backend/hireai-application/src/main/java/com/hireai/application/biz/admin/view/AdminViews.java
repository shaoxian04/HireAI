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
                           BigDecimal escrowHeld, BigDecimal commissionEarned,
                           /** AI Review fees charged (refunded ones excluded), S10. */
                           BigDecimal aiReviewFeesEarned) {}

    public record DisputeRow(UUID disputeId, UUID taskId, String taskTitle, String status,
                             String reasonCategory, Instant createdAt, String clientName,
                             boolean hasArbitratorRuling, boolean needsAttention) {}

    /**
     * Full evidence a DAO assembles for the dispute detail (task submission + agent + result).
     * {@code contractJson} is the task's frozen Contract, the binding terms (Hard Invariant #4).
     */
    public record Evidence(UUID taskId, String taskTitle, String taskDescription, String clientName,
                           String clientReason, BigDecimal budget, String category, String outputFormat,
                           Instant submittedAt, Instant resultReceivedAt,
                           String agentName, String builderName, BigDecimal agentReputation, BigDecimal agentPrice,
                           String contractJson, String resultPayloadJson, String resultUrl, String agentStatus) {}

    /**
     * The result's frozen DeliverySnapshot (D24), null when it has none. The admin judges this copy
     * (download: {@code GET /api/admin/disputes/{id}/deliverable}); {@code resultUrl} only says where
     * it was fetched from.
     */
    public record DeliverableView(String mediaType, String declaredMediaType, long sizeBytes, String sha256,
                                  Instant fetchedAt) {}

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
     * (so the money math is never duplicated in the UI), and the dispute fees the side(s) it goes against
     * would bear (loser pays, D43: the arbitration cost, plus the Appeal Fee once appealed, each capped).
     * Amounts are indicative — the actual movement still happens deterministically in the domain at
     * apply-time (Inv #3), and a fee a wallet cannot cover is borne by the platform. When the AI arbitrator
     * produced no ruling the arbitration cost is waived ({@code arbitrationCostWaived}) and left out.
     */
    public record SettlementPreview(BigDecimal budget, BigDecimal fulfilledPayout, BigDecimal fulfilledCommission,
                                    BigDecimal notFulfilledRefund, BigDecimal partialBuilderNet,
                                    BigDecimal partialClientRefund, BigDecimal fulfilledClientFees,
                                    BigDecimal notFulfilledBuilderFees, BigDecimal partialClientFees,
                                    BigDecimal partialBuilderFees, boolean arbitrationCostWaived) {}

    public record DisputeDetail(UUID disputeId, UUID taskId, String taskTitle, String taskDescription,
                                String status, String reasonCategory, String clientReason,
                                Instant createdAt, String clientName,
                                BigDecimal budget, String category, String outputFormat,
                                Instant submittedAt, Instant resultReceivedAt,
                                String agentName, String builderName, BigDecimal agentReputation, BigDecimal agentPrice,
                                String contractJson, String resultPayloadJson, String resultUrl, String agentStatus,
                                DeliverableView deliverable,
                                boolean actionable, SettlementPreview settlementPreview, List<RulingView> rulings) {}
}
