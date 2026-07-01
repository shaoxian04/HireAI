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

    public record Evidence(UUID taskId, String taskTitle, String taskDescription, String clientName,
                           String outputSpecJson, String resultPayloadJson, String resultUrl,
                           String agentStatus) {}

    public record TaskRow(UUID id, String title, String status, BigDecimal budget, String clientName,
                          Instant createdAt) {}

    public record UserRow(UUID id, String name, String email, List<String> roles,
                          BigDecimal availableBalance, BigDecimal escrowBalance) {}

    public record AgentRow(UUID id, String name, String status, String builderName,
                           BigDecimal reputationScore, BigDecimal price) {}

    public record RulingView(int tier, String decidedBy, String category, String rationale,
                             Instant decidedAt) {}

    public record DisputeDetail(UUID disputeId, UUID taskId, String taskTitle, String taskDescription,
                                String status, String reasonCategory, Instant createdAt, String clientName,
                                String outputSpecJson, String resultPayloadJson, String resultUrl,
                                String agentStatus, boolean actionable, List<RulingView> rulings) {}
}
