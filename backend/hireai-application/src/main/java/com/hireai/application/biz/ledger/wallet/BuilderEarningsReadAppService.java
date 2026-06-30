package com.hireai.application.biz.ledger.wallet;

import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builder earnings READ use case. Derives all amounts from the tasks table via
 * SettlementPolicy — never by summing ledger rows, because PAYOUT entries exist on both
 * wallets of a settlement and are ambiguous in the legal self-settle case (a client
 * accepting their own agent's work). Equal to the ledger credit by construction: settlement
 * computed the same net from the same task row — netOf(budget) for a full ACCEPT, or the
 * split net netOf(builderShareOnSplit(budget)) for a PARTIALLY_ACCEPTED dispute outcome.
 * Display amounts only — amounts of record live in the ledger.
 */
@Validated
public interface BuilderEarningsReadAppService {

    Earnings earningsFor(@NonNull UUID userId);

    record Earnings(BigDecimal lifetimeEarned, BigDecimal pendingIfAccepted, int paidTaskCount,
                    List<AgentEarnings> perAgent, List<Payout> payouts) {
    }

    record AgentEarnings(UUID agentId, String agentName, BigDecimal earned,
                         BigDecimal pendingIfAccepted, int paidTaskCount) {
    }

    record Payout(UUID taskId, String taskTitle, String agentName,
                  BigDecimal amount, Instant settledAt) {
    }
}
