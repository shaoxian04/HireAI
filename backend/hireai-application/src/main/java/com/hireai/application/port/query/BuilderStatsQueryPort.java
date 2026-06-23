package com.hireai.application.port.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Builder-private statistics over real tasks routed to any version of the agent. */
public interface BuilderStatsQueryPort {

    StatsRow stats(UUID agentId);

    List<TrendPointRow> trend(UUID agentId, int days);

    List<RecentTaskRow> recentTasks(UUID agentId, int limit);

    record StatsRow(int total, int completed, int failed, int open,
                    BigDecimal creditsInEscrow, BigDecimal potentialEarnings,
                    Double avgTurnaroundSeconds, int onTimeCount, int withResultCount) {
    }

    record TrendPointRow(LocalDate day, int count) {
    }

    record RecentTaskRow(UUID id, String title, String status, Instant createdAt) {
    }

    record StatsBundle(StatsRow stats, List<TrendPointRow> trend, List<RecentTaskRow> recent) {
    }
}
