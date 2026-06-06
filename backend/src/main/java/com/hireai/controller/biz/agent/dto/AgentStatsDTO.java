package com.hireai.controller.biz.agent.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AgentStatsDTO(Volume volume, Performance performance, Earnings earnings,
                            List<TrendPoint> trend, List<RecentTask> recentTasks) {

    public record Volume(int total, int completed, int failed, int open, Double successRate) {}

    public record Performance(Double avgTurnaroundSeconds, Double onTimeRate) {}

    public record Earnings(BigDecimal creditsInEscrow, BigDecimal potentialEarnings) {}

    public record TrendPoint(LocalDate day, int count) {}

    public record RecentTask(UUID id, String title, String status, Instant createdAt) {}
}
