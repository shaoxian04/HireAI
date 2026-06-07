package com.hireai.wallet;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.AgentEarnings;
import com.hireai.application.biz.wallet.BuilderEarningsReadAppService.Earnings;
import com.hireai.application.biz.wallet.impl.BuilderEarningsReadAppServiceImpl;
import com.hireai.application.port.query.BuilderEarningsQueryPort;
import com.hireai.application.port.query.BuilderEarningsQueryPort.OwnedAgentRow;
import com.hireai.application.port.query.BuilderEarningsQueryPort.RoutedTaskRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The SettlementPolicy fold: which task rows count, how they round, how they group.
 * Wire-level concerns (SQL, JSON) are covered by the integration and slice tests.
 */
@ExtendWith(MockitoExtension.class)
class BuilderEarningsReadAppServiceImplTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID AGENT_A = UUID.randomUUID();
    private static final UUID AGENT_B = UUID.randomUUID();

    @Mock BuilderEarningsQueryPort queryPort;
    @InjectMocks BuilderEarningsReadAppServiceImpl service;

    private RoutedTaskRow row(UUID agentId, String agentName, String budget,
                              String status, String resolution, Instant resolvedAt) {
        return new RoutedTaskRow(UUID.randomUUID(), "task " + budget, new BigDecimal(budget),
                status, resolution, resolvedAt, agentId, agentName);
    }

    private void stub(List<RoutedTaskRow> rows, List<OwnedAgentRow> agents) {
        when(queryPort.routedTasks(OWNER)).thenReturn(rows);
        when(queryPort.ownedAgents(OWNER)).thenReturn(agents);
    }

    @Test
    void acceptedTasksFoldToLifetimeEarnedNetOfCommission() {
        stub(List.of(
                row(AGENT_A, "A", "12.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "20.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T11:00:00Z")),
                row(AGENT_A, "A", "30.00", "RESOLVED", "REJECTED", Instant.parse("2026-06-07T12:00:00Z")),
                row(AGENT_A, "A", "40.00", "FAILED", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // 12.00 -> net 10.20, 20.00 -> net 17.00; rejected + failed contribute nothing
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("27.20");
        assertThat(e.paidTaskCount()).isEqualTo(2);
        assertThat(e.payouts()).hasSize(2);
    }

    @Test
    void pendingSumsOpenStatusesOnly() {
        stub(List.of(
                row(AGENT_A, "A", "10.00", "QUEUED", null, null),
                row(AGENT_A, "A", "12.00", "EXECUTING", null, null),
                row(AGENT_A, "A", "20.00", "RESULT_RECEIVED", null, null),
                row(AGENT_A, "A", "4.00", "AWAITING_CAPACITY", null, null),
                row(AGENT_A, "A", "99.00", "RESOLVED", "ACCEPTED", Instant.now()),
                row(AGENT_A, "A", "99.00", "TIMED_OUT", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // nets: 8.50 + 10.20 + 17.00 + 3.40 = 39.10 (accepted/timed-out are NOT pending)
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("39.10");
    }

    @Test
    void perAgentBreakdownIncludesZeroRowAgents() {
        stub(List.of(
                row(AGENT_A, "A", "12.00", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "10.00", "RESULT_RECEIVED", null, null)),
                List.of(new OwnedAgentRow(AGENT_A, "A"), new OwnedAgentRow(AGENT_B, "B")));

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.perAgent()).containsExactly(
                new AgentEarnings(AGENT_A, "A", new BigDecimal("10.20"), new BigDecimal("8.50"), 1),
                new AgentEarnings(AGENT_B, "B", new BigDecimal("0.00"), new BigDecimal("0.00"), 0));
    }

    @Test
    void payoutsAreNewestFirstAndCappedAt50() {
        Instant base = Instant.parse("2026-06-01T00:00:00Z");
        List<RoutedTaskRow> rows = new ArrayList<>(IntStream.range(0, 60)
                .mapToObj(i -> row(AGENT_A, "A", "10.00", "RESOLVED", "ACCEPTED",
                        base.plusSeconds(i)))
                .toList());
        stub(rows, List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.payouts()).hasSize(50);
        assertThat(e.payouts().get(0).settledAt()).isEqualTo(base.plusSeconds(59)); // newest first
        assertThat(e.payouts().get(49).settledAt()).isEqualTo(base.plusSeconds(10));
        assertThat(e.paidTaskCount()).isEqualTo(60); // the COUNT is not capped, only the list
    }

    @Test
    void zeroCommissionFlipPoint() {
        stub(List.of(
                row(AGENT_A, "A", "0.03", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T10:00:00Z")),
                row(AGENT_A, "A", "0.04", "RESOLVED", "ACCEPTED", Instant.parse("2026-06-07T11:00:00Z"))),
                List.of(new OwnedAgentRow(AGENT_A, "A")));

        Earnings e = service.earningsFor(OWNER);

        // 0.03: commission rounds to 0.00 -> net 0.03; 0.04: commission 0.01 -> net 0.03
        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.06");
    }

    @Test
    void callerWithNoAgentsGetsEmptyEarnings() {
        stub(List.of(), List.of());

        Earnings e = service.earningsFor(OWNER);

        assertThat(e.lifetimeEarned()).isEqualByComparingTo("0.00");
        assertThat(e.pendingIfAccepted()).isEqualByComparingTo("0.00");
        assertThat(e.paidTaskCount()).isZero();
        assertThat(e.perAgent()).isEmpty();
        assertThat(e.payouts()).isEmpty();
    }
}
