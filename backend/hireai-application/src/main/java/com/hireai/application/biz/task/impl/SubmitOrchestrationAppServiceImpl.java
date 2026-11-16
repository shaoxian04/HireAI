package com.hireai.application.biz.task.impl;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.extraction.model.ContractExtraction;
import com.hireai.domain.biz.acceptance.extraction.model.ExtractionRecord;
import com.hireai.application.biz.acceptance.extraction.ContractDraftAppService;

import com.hireai.application.biz.task.DirectBookingAppService;
import com.hireai.application.biz.task.SubmitContext;
import com.hireai.application.biz.task.SubmitFingerprint;
import com.hireai.application.biz.task.SubmitOrchestrationAppService;
import com.hireai.application.biz.task.TaskWriteAppService;
import com.hireai.application.port.query.SpendReadPort;
import com.hireai.domain.biz.offering.category.repository.CategoryRepository;
import com.hireai.domain.biz.task.idempotency.model.IdempotencyRecord;
import com.hireai.domain.biz.identity.apikey.model.SpendCaps;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.idempotency.repository.IdempotencyRepository;
import com.hireai.domain.biz.task.info.DirectBookingInfo;
import com.hireai.domain.biz.task.item.info.ItemInput;
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
    private final CategoryRepository categoryRepository;
    private final Clock clock;
    /** Requirement extraction for a mode-1 (description-only) submit (spec S6). */
    private final ContractDraftAppService contractDraftAppService;

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
                                             CategoryRepository categoryRepository,
                                             Clock clock,
                                             ContractDraftAppService contractDraftAppService) {
        this.taskWriteAppService = taskWriteAppService;
        this.directBookingAppService = directBookingAppService;
        this.idempotencyRepository = idempotencyRepository;
        this.apiKeyTaskRepository = apiKeyTaskRepository;
        this.spendReadPort = spendReadPort;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
        this.contractDraftAppService = contractDraftAppService;
    }

    /** The proxied bean in production; {@code this} in a direct-construction unit test. */
    private SubmitOrchestrationAppServiceImpl self() {
        return self != null ? self : this;
    }

    /**
     * Mode 1 (no Contract, spec S6 D28): the description is extracted onto the output spec's Template
     * (outside any transaction: the LLM call may take seconds) and the result is adopted; submitting is
     * acceptance. Mode 2 (a Contract): no extraction, the Contract is the only basis for acceptance. The
     * idempotency fingerprint is the CLIENT's request, so a mode-1 retry matches even though extraction
     * is not deterministic, and a replay returns the first Task without extracting again.
     */
    @Override
    public UUID submitRouted(SubmitContext ctx, TaskSubmitInfo submitted) {
        // An unknown slug is a 400 naming the valid ones, before anything is frozen (#80).
        String category = categoryRepository.load().requireKnown(submitted.category());
        TaskSubmitInfo info = new TaskSubmitInfo(submitted.clientId(), submitted.title(),
                submitted.description(), submitted.budget(), submitted.outputSpec(), category,
                submitted.deadlineSeconds(), submitted.contract(), submitted.items());
        String fingerprint = fingerprint(info);
        ContractExtraction extraction = null;
        if (info.contract() == null && info.outputSpec() != null) {
            if (ctx.hasIdempotencyKey()) {
                Optional<IdempotencyRecord> existing = idempotencyRepository.find(ctx.ownerId(), ctx.idempotencyKey());
                if (existing.isPresent()) {
                    return resolveExisting(existing.get(), fingerprint);
                }
            }
            extraction = contractDraftAppService.draft(ctx.ownerId(), info.title(), info.description(),
                    info.outputSpec().acceptanceCriteria(), info.outputSpec().toContract());
            info = new TaskSubmitInfo(info.clientId(), info.title(), info.description(), info.budget(),
                    info.outputSpec(), info.category(), info.deadlineSeconds(), extraction.contract(), info.items());
        }
        try {
            return self().submitRoutedTx(ctx, info, fingerprint, extraction == null ? null : extraction.record());
        } catch (IdempotencyRaceException e) {
            return self().resolveRaceWinner(ctx.ownerId(), ctx.idempotencyKey(), e.fingerprint());
        }
    }

    @Override
    public UUID submitDirect(SubmitContext ctx, DirectBookingInfo booked) {
        // The category is optional on a booking; when given it must be a listed slug (#80).
        DirectBookingInfo info = booked.category() == null ? booked
                : new DirectBookingInfo(booked.clientId(), booked.title(), booked.description(),
                        booked.budget(), booked.agentId(),
                        categoryRepository.load().requireKnown(booked.category()), booked.deadlineSeconds(),
                        booked.contract(), booked.items(), booked.engagementId(), booked.aiReviewCriteria(),
                        booked.inspectionLevel());
        try {
            return self().submitDirectTx(ctx, info);
        } catch (IdempotencyRaceException e) {
            return self().resolveRaceWinner(ctx.ownerId(), ctx.idempotencyKey(), e.fingerprint());
        }
    }

    @Transactional
    public UUID submitRoutedTx(SubmitContext ctx, TaskSubmitInfo info, String fingerprint, ExtractionRecord extraction) {
        // Spend caps count what is frozen: for a multi-Item Task, the per-Item price × the Item count.
        return orchestrate(ctx, fingerprint, info.escrow().value(), () -> {
            UUID taskId = taskWriteAppService.submit(info);
            if (extraction != null) {
                taskWriteAppService.recordExtraction(taskId, extraction);
            }
            return taskId;
        });
    }

    @Transactional
    public UUID submitDirectTx(SubmitContext ctx, DirectBookingInfo info) {
        return orchestrate(ctx, fingerprintDirect(info), frozenBy(ctx, info), () -> directBookingAppService.book(info));
    }

    /**
     * What a booking freezes, which is what the spend caps count: the escrow, plus the AI Review fee the
     * booking freezes beside it (the same quote the booking prices it with). A quote that is not offered
     * adds nothing: the booking itself then refuses.
     */
    private BigDecimal frozenBy(SubmitContext ctx, DirectBookingInfo info) {
        BigDecimal escrow = info.escrow().value();
        if (!ctx.isApiKey() || info.aiReviewCriteria().isEmpty()) {
            return escrow;
        }
        var quote = directBookingAppService.quoteAiReview(info.agentId(), info.contract(), info.budget(),
                info.aiReviewCriteria().size());
        return quote != null && quote.available() ? escrow.add(quote.fee().value()) : escrow;
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
                info.budget().value(), specJson(info.outputSpec()) + deadlinePart(info.deadlineSeconds())
                        + contractPart(info.contract()) + itemsPart(info.items()));
    }

    private String fingerprintDirect(DirectBookingInfo info) {
        // Direct booking adopts the agent's spec later; fingerprint the client-supplied fields + agent id.
        // A chosen category joins the fingerprint; without one it stays as before.
        String kind = info.category() == null ? "direct" : "direct|" + info.category();
        return SubmitFingerprint.of(info.title(), info.description(),
                info.agentId().toString(), info.budget().value(), kind + deadlinePart(info.deadlineSeconds())
                        + contractPart(info.contract()) + itemsPart(info.items())
                        + (info.engagementId() == null ? "" : "|engagement=" + info.engagementId())
                        + aiReviewPart(info.aiReviewCriteria()));
    }

    /** AI Review criteria join the fingerprint (spec S10); without any it stays as before. */
    private static String aiReviewPart(java.util.List<String> criteria) {
        if (criteria.isEmpty()) {
            return "";
        }
        StringBuilder part = new StringBuilder("|aiReview=").append(criteria.size());
        criteria.forEach(c -> part.append('|').append(c.length()).append(':').append(c));
        return part.toString();
    }

    /**
     * A Contract joins the fingerprint (#91; params are held sorted, so equal Contracts render alike);
     * without one it stays as before.
     */
    private static String contractPart(Contract contract) {
        return contract == null ? "" : "|contract=" + contract.template() + contract.params();
    }

    /** A deadline joins the fingerprint (as relative seconds, so a retry matches); without one it stays as before. */
    private static String deadlinePart(Long deadlineSeconds) {
        return deadlineSeconds == null ? "" : "|deadline=" + deadlineSeconds;
    }

    /**
     * A multi-Item Task's Items join the fingerprint (count, then each input and its document, if any);
     * without Items it stays as before.
     */
    private static String itemsPart(java.util.List<ItemInput> items) {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder part = new StringBuilder("|items=").append(items.size());
        items.forEach(item -> {
            String input = item.input() == null ? "" : item.input();
            part.append('|').append(input.length()).append(':').append(input);
            if (item.hasSource()) {
                part.append("@").append(item.sourceSnapshotId());
            }
        });
        return part.toString();
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
