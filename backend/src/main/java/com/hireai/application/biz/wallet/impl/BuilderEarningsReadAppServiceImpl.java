package com.hireai.application.biz.wallet.impl;

import com.hireai.application.biz.wallet.BuilderEarningsReadAppService;
import com.hireai.application.port.query.BuilderEarningsQueryPort;
import com.hireai.application.port.query.BuilderEarningsQueryPort.OwnedAgentRow;
import com.hireai.application.port.query.BuilderEarningsQueryPort.RoutedTaskRow;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.wallet.service.SettlementPolicy;
import com.hireai.domain.shared.model.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Folds routed-task rows through SettlementPolicy. O(agents x tasks) — fine at demo scale;
 * the payout list is capped at {@link #PAYOUT_HISTORY_LIMIT}, the totals are not.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BuilderEarningsReadAppServiceImpl implements BuilderEarningsReadAppService {

    /** Escrow still held and routed to one of the caller's agents — would pay out on accept. */
    private static final Set<String> PENDING_STATUSES = Set.of(
            TaskStatus.QUEUED.name(),
            TaskStatus.EXECUTING.name(),
            TaskStatus.RESULT_RECEIVED.name(),
            TaskStatus.PENDING_REVIEW.name(),
            TaskStatus.AWAITING_CAPACITY.name());

    private static final int PAYOUT_HISTORY_LIMIT = 50;

    private final BuilderEarningsQueryPort queryPort;

    @Override
    public Earnings earningsFor(UUID userId) {
        List<RoutedTaskRow> rows = queryPort.routedTasks(userId);
        List<OwnedAgentRow> agents = queryPort.ownedAgents(userId);

        Money lifetime = sumNet(rows.stream().filter(this::accepted).toList());
        Money pending = sumNet(rows.stream().filter(this::pending).toList());
        int paidCount = (int) rows.stream().filter(this::accepted).count();

        List<Payout> payouts = rows.stream()
                .filter(this::accepted)
                .sorted(Comparator.comparing(RoutedTaskRow::resolvedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(PAYOUT_HISTORY_LIMIT)
                .map(r -> new Payout(r.taskId(), r.title(), r.agentName(), net(r).value(), r.resolvedAt()))
                .toList();

        List<AgentEarnings> perAgent = agents.stream()
                .map(a -> agentEarnings(a, rows))
                .toList();

        return new Earnings(lifetime.value(), pending.value(), paidCount, perAgent, payouts);
    }

    private AgentEarnings agentEarnings(OwnedAgentRow agent, List<RoutedTaskRow> allRows) {
        List<RoutedTaskRow> mine = allRows.stream()
                .filter(r -> agent.agentId().equals(r.agentId()))
                .toList();
        Money earned = sumNet(mine.stream().filter(this::accepted).toList());
        Money pending = sumNet(mine.stream().filter(this::pending).toList());
        int paid = (int) mine.stream().filter(this::accepted).count();
        return new AgentEarnings(agent.agentId(), agent.agentName(),
                earned.value(), pending.value(), paid);
    }

    private boolean accepted(RoutedTaskRow row) {
        return TaskStatus.RESOLVED.name().equals(row.status())
                && TaskResolution.ACCEPTED.name().equals(row.resolution());
    }

    private boolean pending(RoutedTaskRow row) {
        return PENDING_STATUSES.contains(row.status());
    }

    private Money net(RoutedTaskRow row) {
        return SettlementPolicy.netOf(Money.of(row.budget()));
    }

    private Money sumNet(List<RoutedTaskRow> rows) {
        return rows.stream().map(this::net).reduce(Money.ZERO, Money::add);
    }
}
