package com.hireai.application.biz.task.impl;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitFingerprint;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.apikey.model.IdempotencyRecord;
import com.hireai.domain.biz.apikey.model.SpendCaps;
import com.hireai.domain.biz.apikey.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.apikey.repository.IdempotencyRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.info.TaskSubmitInfo;
import com.hireai.domain.biz.task.model.OutputSpec;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The submit edge. Each {@code *Tx} core runs in one transaction (REQUIRED): the delegated submit joins
 * it, so a later idempotency-insert failure rolls back the escrow freeze too (no double-freeze). The
 * concurrent-race re-read runs in a SEPARATE transaction (the outer one is doomed after the
 * constraint violation), so it can see the winner's committed row.
 *
 * <p>The two public entry points are intentionally NON-transactional: they enter the {@code @Transactional}
 * {@code *Tx} core THROUGH the Spring proxy ({@link #self()}) — self-invocation would bypass it — and
 * translate a lost-race {@link IdempotencyRaceException} into a fresh-transaction winner re-read. The
 * proxy is field-injected {@code @Lazy} to break the self-referential construction cycle; in a plain
 * unit test the field stays {@code null} and {@link #self()} falls back to {@code this} (the tx
 * annotations are inert without a proxy — the test exercises the logic, not the tx boundary).
 */
@Service
@Slf4j
public class SubmitOrchestrationAppServiceImpl implements SubmitOrchestrationAppService {

    private static final Duration DAY = Duration.ofHours(24);

    private final TaskWriteAppService taskWriteAppService;
    private final DirectBookingAppService directBookingAppService;
    private final IdempotencyRepository idempotencyRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final SpendReadPort spendReadPort;
    private final Clock clock;

    /** The Spring proxy of THIS bean (so the {@code @Transactional} {@code *Tx}/re-read methods are
     *  entered through the proxy). {@code null} when constructed directly in a unit test. */
    @Autowired
    @Lazy
    private SubmitOrchestrationAppServiceImpl self;

    public SubmitOrchestrationAppServiceImpl(TaskWriteAppService taskWriteAppService,
                                             DirectBookingAppService directBookingAppService,
                                             IdempotencyRepository idempotencyRepository,
                                             ApiKeyTaskRepository apiKeyTaskRepository,
                                             SpendReadPort spendReadPort,
                                             Clock clock) {
        this.taskWriteAppService = taskWriteAppService;
        this.directBookingAppService = directBookingAppService;
        this.idempotencyRepository = idempotencyRepository;
        this.apiKeyTaskRepository = apiKeyTaskRepository;
        this.spendReadPort = spendReadPort;
        this.clock = clock;
    }

    /** The proxied bean in production; {@code this} in a direct-construction unit test. */
    private SubmitOrchestrationAppServiceImpl self() {
        return self != null ? self : this;
    }

    @Override
    public UUID submitRouted(SubmitContext ctx, TaskSubmitInfo info) {
        try {
            return self().submitRoutedTx(ctx, info);
        } catch (IdempotencyRaceException e) {
            return self().resolveRaceWinner(ctx.ownerId(), ctx.idempotencyKey(), e.fingerprint());
        }
    }

    @Override
    public UUID submitDirect(SubmitContext ctx, DirectBookingInfo info) {
        try {
            return self().submitDirectTx(ctx, info);
        } catch (IdempotencyRaceException e) {
            return self().resolveRaceWinner(ctx.ownerId(), ctx.idempotencyKey(), e.fingerprint());
        }
    }

    @Transactional
    public UUID submitRoutedTx(SubmitContext ctx, TaskSubmitInfo info) {
        return orchestrate(ctx, fingerprint(info), info.budget().value(),
                () -> taskWriteAppService.submit(info));
    }

    @Transactional
    public UUID submitDirectTx(SubmitContext ctx, DirectBookingInfo info) {
        return orchestrate(ctx, fingerprintDirect(info), info.budget().value(),
                () -> directBookingAppService.book(info));
    }

    /**
     * Shared flow: idempotency pre-check → spend-cap check → submit (joins this tx) → attribution →
     * idempotency insert (UNIQUE guard). A concurrent-retry UNIQUE violation dooms this tx (undoing
     * the freeze); it is signalled up as {@link IdempotencyRaceException} and resolved by re-reading
     * the winner in a new tx.
     */
    private UUID orchestrate(SubmitContext ctx, String fingerprint, BigDecimal budget,
                             Supplier<UUID> submit) {
        if (ctx.hasIdempotencyKey()) {
            Optional<IdempotencyRecord> existing =
                    idempotencyRepository.find(ctx.ownerId(), ctx.idempotencyKey());
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), fingerprint);
            }
        }
        checkSpendCap(ctx, budget);

        UUID taskId = submit.get();

        if (ctx.isApiKey()) {
            apiKeyTaskRepository.attribute(taskId, ctx.apiKeyId(), budget, clock.instant());
        }
        if (ctx.hasIdempotencyKey()) {
            try {
                idempotencyRepository.insert(IdempotencyRecord.create(
                        ctx.ownerId(), ctx.idempotencyKey(), fingerprint, taskId, clock.instant()));
            } catch (DataIntegrityViolationException race) {
                // A concurrent identical retry won the UNIQUE. This tx is doomed → the freeze rolls
                // back (no double-freeze). Re-read the winner in a fresh tx and return its task.
                log.info("Idempotency race on ({}, {}); re-reading winner",
                        ctx.ownerId(), ctx.idempotencyKey());
                throw new IdempotencyRaceException(fingerprint);
            }
        }
        return taskId;
    }

    private UUID resolveExisting(IdempotencyRecord record, String fingerprint) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new DomainException(ResultCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency-Key reused with a different request payload");
        }
        return record.taskId();
    }

    private void checkSpendCap(SubmitContext ctx, BigDecimal budget) {
        if (!ctx.isApiKey() || (ctx.spendCap() == null && ctx.dailySpendCap() == null)) {
            return; // human request, or uncapped key → nothing to read
        }
        BigDecimal committed = ctx.spendCap() == null
                ? BigDecimal.ZERO : spendReadPort.committedFor(ctx.apiKeyId());
        BigDecimal daily = ctx.dailySpendCap() == null
                ? BigDecimal.ZERO : spendReadPort.dailySpendFor(ctx.apiKeyId(), clock.instant().minus(DAY));
        SpendCaps.of(ctx.spendCap(), ctx.dailySpendCap()).checkOrThrow(committed, daily, budget);
    }

    private String fingerprint(TaskSubmitInfo info) {
        return SubmitFingerprint.of(info.title(), info.description(), info.category(),
                info.budget().value(), specJson(info.outputSpec()));
    }

    private String fingerprintDirect(DirectBookingInfo info) {
        // Direct booking adopts the agent's spec later; fingerprint the client-supplied fields + agent id.
        return SubmitFingerprint.of(info.title(), info.description(),
                info.agentId().toString(), info.budget().value(), "direct");
    }

    /** Stable canonical rendering of the spec for the fingerprint (must match the test helper). */
    private String specJson(OutputSpec spec) {
        return spec == null ? "∅"
                : spec.format() + "|" + nz(spec.schema()) + "|" + nz(spec.acceptanceCriteria());
    }

    private static String nz(String s) {
        return s == null ? "∅" : s;
    }

    /**
     * New-transaction re-read of the winner after a lost race. Called by the public entry points once
     * the doomed outer tx has rolled back. Returns the winning task (fingerprint match) or 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public UUID resolveRaceWinner(UUID ownerId, String idempotencyKey, String fingerprint) {
        return idempotencyRepository.find(ownerId, idempotencyKey)
                .map(r -> resolveExisting(r, fingerprint))
                .orElseThrow(() -> new DomainException(ResultCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency race unresolved"));
    }

    /** Signals a lost idempotency race so the boundary re-reads the winner in a fresh transaction. */
    public static final class IdempotencyRaceException extends RuntimeException {
        private final String fingerprint;

        public IdempotencyRaceException(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public String fingerprint() {
            return fingerprint;
        }
    }
}
