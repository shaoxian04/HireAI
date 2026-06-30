package com.hireai.application.biz.ledger.wallet;

import com.hireai.application.biz.ledger.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.ledger.wallet.impl.BuilderEarningsReadAppServiceImpl;
import com.hireai.application.port.query.BuilderEarningsQueryPort;
import com.hireai.application.port.query.BuilderEarningsQueryPort.OwnedAgentRow;
import com.hireai.application.port.query.BuilderEarningsQueryPort.RoutedTaskRow;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the SPLIT-outcome earnings accounting: a PARTIALLY_ACCEPTED task pays the builder
 * the split net (42.50 on a 100 budget), not the full netOf(budget) (85.00). Regression guard
 * for Module 4 Phase 2 — the resolution alone no longer determines the payout amount.
 */
class BuilderEarningsReadAppServiceImplTest {

    private final BuilderEarningsQueryPort queryPort = mock(BuilderEarningsQueryPort.class);
    private final BuilderEarningsReadAppServiceImpl service = new BuilderEarningsReadAppServiceImpl(queryPort);

    private static RoutedTaskRow row(String title, String resolution, UUID agentId) {
        return new RoutedTaskRow(UUID.randomUUID(), title, new BigDecimal("100.00"),
                TaskStatus.RESOLVED.name(), resolution, Instant.now(), agentId, "Agent A");
    }

    @Test
    void splitTaskEarnsHalfNetWhileFullAcceptEarnsFullNet() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(queryPort.routedTasks(userId)).thenReturn(List.of(
                row("full", TaskResolution.ACCEPTED.name(), agentId),
                row("partial", TaskResolution.PARTIALLY_ACCEPTED.name(), agentId)));
        when(queryPort.ownedAgents(userId)).thenReturn(List.of(new OwnedAgentRow(agentId, "Agent A")));

        Earnings earnings = service.earningsFor(userId);

        // 85.00 (full accept) + 42.50 (split net) = 127.50
        assertThat(earnings.lifetimeEarned()).isEqualByComparingTo("127.50");
        assertThat(earnings.paidTaskCount()).isEqualTo(2);
        assertThat(earnings.perAgent()).singleElement()
                .satisfies(a -> assertThat(a.earned()).isEqualByComparingTo("127.50"));
        // the split task's recorded payout is the half-net, not the full net
        assertThat(earnings.payouts())
                .filteredOn(p -> "partial".equals(p.taskTitle()))
                .singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo("42.50"));
    }

    @Test
    void rejectedTaskEarnsNothing() {
        UUID userId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        when(queryPort.routedTasks(userId)).thenReturn(List.of(row("rej", TaskResolution.REJECTED.name(), agentId)));
        when(queryPort.ownedAgents(userId)).thenReturn(List.of(new OwnedAgentRow(agentId, "Agent A")));

        Earnings earnings = service.earningsFor(userId);

        assertThat(earnings.lifetimeEarned()).isEqualByComparingTo("0.00");
        assertThat(earnings.paidTaskCount()).isZero();
    }
}
