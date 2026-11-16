package com.hireai.application.biz.task.sampling.impl;

import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.application.biz.task.OutputSpecJsonMapper;
import com.hireai.application.biz.task.item.TaskItemAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.application.biz.task.sampling.SamplingAppService;
import com.hireai.application.port.agent.AgentNoticePort;
import com.hireai.application.port.agent.RejectionNotice;
import com.hireai.application.port.security.DispatchTokenClaims;
import com.hireai.application.port.security.DispatchTokenService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.event.ItemRejectedDomainEvent;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.model.FlaggedShareCap;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.model.RejectionWindows;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.routing.info.ReworkRequest;
import com.hireai.domain.biz.task.sampling.enums.InspectionLevel;
import com.hireai.domain.biz.task.sampling.enums.InspectionStatus;
import com.hireai.domain.biz.task.sampling.enums.SampleItemOutcome;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;
import com.hireai.domain.biz.task.sampling.model.ProRata;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;
import com.hireai.domain.biz.task.sampling.model.SampleSizeTable;
import com.hireai.domain.biz.task.sampling.model.SampleTally;
import com.hireai.domain.biz.task.sampling.model.SamplingDecision;
import com.hireai.domain.biz.task.sampling.model.SamplingPlan;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;
import com.hireai.domain.biz.task.sampling.repository.ItemRejectionRepository;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thin orchestration of sampling (spec S8): the table, the draw, the stage verdicts and the pro-rata
 * arithmetic live in the domain ({@link SampleSizeTable}, {@link SampleDrawModel}, {@link SamplingDecision},
 * {@link ProRata}, {@link InspectionModel}, {@link TaskItemModel}); the rejection rounds reuse #95's
 * {@link RejectionModel}; money moves through {@link TaskItemAppService}'s per-Item settlements.
 */
@Service
@Slf4j
@Transactional
public class SamplingAppServiceImpl implements SamplingAppService {

    static final int MAX_NOTICE_ATTEMPTS = 5;
    /** The whole-Task rework after a failed sample happens once (D19). */
    private static final int SAMPLE_REWORKS = 1;

    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final TaskItemAppService taskItemAppService;
    private final InspectionRepository inspectionRepository;
    private final ItemRejectionRepository itemRejectionRepository;
    private final RoutingAppService routingAppService;
    private final AgentRepository agentRepository;
    private final DispatchTokenService dispatchTokenService;
    private final AgentNoticePort agentNoticePort;
    private final OutputSpecJsonMapper outputSpecJsonMapper;
    private final SourceSnapshotAppService sourceSnapshotAppService;
    private final ApplicationEventPublisher eventPublisher;
    private final RejectionWindows itemWindows;
    private final ReviewWindow reviewWindow;
    private final Clock clock;
    private final FlaggedShareCap flaggedShareCap;
    private final String publicBaseUrl;
    private final com.hireai.application.biz.acceptance.AcceptanceJsonMapper acceptanceJsonMapper;
    private final SecureRandom seeds = new SecureRandom();

    public SamplingAppServiceImpl(TaskRepository taskRepository, TaskItemRepository taskItemRepository,
                                  TaskItemAppService taskItemAppService, InspectionRepository inspectionRepository,
                                  ItemRejectionRepository itemRejectionRepository,
                                  RoutingAppService routingAppService, AgentRepository agentRepository,
                                  DispatchTokenService dispatchTokenService, AgentNoticePort agentNoticePort,
                                  OutputSpecJsonMapper outputSpecJsonMapper,
                                  SourceSnapshotAppService sourceSnapshotAppService,
                                  ApplicationEventPublisher eventPublisher, RejectionWindows windows,
                                  ReviewWindow reviewWindow, Clock clock, FlaggedShareCap flaggedShareCap,
                                  @Value("${hireai.platform.public-base-url:http://localhost:8080}")
                                  String publicBaseUrl,
                                  com.hireai.application.biz.acceptance.AcceptanceJsonMapper acceptanceJsonMapper) {
        this.acceptanceJsonMapper = acceptanceJsonMapper;
        this.taskRepository = taskRepository;
        this.taskItemRepository = taskItemRepository;
        this.taskItemAppService = taskItemAppService;
        this.inspectionRepository = inspectionRepository;
        this.itemRejectionRepository = itemRejectionRepository;
        this.routingAppService = routingAppService;
        this.agentRepository = agentRepository;
        this.dispatchTokenService = dispatchTokenService;
        this.agentNoticePort = agentNoticePort;
        this.outputSpecJsonMapper = outputSpecJsonMapper;
        this.sourceSnapshotAppService = sourceSnapshotAppService;
        this.eventPublisher = eventPublisher;
        // A multi-Item Task's windows count business days (D41); a sampled rejection takes the D20 windows.
        this.itemWindows = windows.countingBusinessDays();
        this.reviewWindow = reviewWindow;
        this.clock = clock;
        this.flaggedShareCap = flaggedShareCap;
        this.publicBaseUrl = publicBaseUrl;
    }

    // ---- delivery and the draws ----

    @Override
    public void onItemsDelivered(TaskModel pendingReview) {
        UUID taskId = pendingReview.id();
        InspectionModel inspection = inspectionRepository.findByTaskId(taskId)
                .orElseGet(() -> InspectionModel.open(taskId, InspectionLevel.DEFAULT));
        if (inspection.status() == InspectionStatus.REWORKING) {
            inspection = inspection.resample(); // the reworked Items are back: round 2
        }
        inspectionRepository.save(inspection);
        if (!inspection.isSampling()) {
            taskItemAppService.resolveIfAllSettled(pendingReview);
            return;
        }
        // The sample is drawn from the Items every check passed plus the flagged Items beyond the cap; a flagged
        // Item within the cap is the reviewer's own extra check (D7) and is never drawn.
        List<Integer> lot = taskItemRepository.findByTaskId(taskId).sampleable(flaggedShareCap).stream()
                .map(TaskItemModel::itemNo).toList();
        if (lot.isEmpty()) {
            // Nothing passed the checks this round: every Item already settled, is on its own path, or
            // awaits the reviewer's own judgement.
            taskItemAppService.resolveIfAllSettled(pendingReview);
            return;
        }
        SamplingPlan plan = SampleSizeTable.plan(lot.size(), inspection.level());
        SampleDrawModel draw = SampleDrawModel.draw(taskId, inspection.round(), 1, plan, seeds.nextLong(), lot,
                clock.instant());
        inspectionRepository.insertDraw(draw);
        log.info("Task {} round {}: sampled {} of {} Items (letter {}, level {}, seed recorded)", taskId,
                inspection.round(), draw.itemNos().size(), lot.size(), plan.codeLetter(), inspection.level());
    }

    // ---- the reviewer ----

    @Override
    public void judge(UUID taskId, UUID clientId, int itemNo, boolean passed, RejectReason reasonCategory,
                      String reason) {
        TaskModel task = loadOwned(taskId, clientId);
        if (task.status() != TaskStatus.PENDING_REVIEW) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " is " + task.status() + "; its Items are not awaiting review");
        }
        Instant now = clock.instant();
        Instant deadline = taskRepository.findReviewWindow(taskId).map(w -> w.deadline()).orElse(null);
        if (ReviewWindow.hasClosed(deadline, now)) {
            // Nothing is judged (or clawed back) after the window: silence already counted as acceptance.
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "The review window for task " + taskId
                    + " closed at " + deadline + "; the Items awaiting review are accepted automatically");
        }
        TaskItemModel item = taskItemRepository.findByTaskId(taskId).item(itemNo);
        if (item.status() != ItemStatus.PENDING_REVIEW) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Item " + itemNo + " is " + item.status() + "; only an Item awaiting review can be judged");
        }
        Optional<InspectionModel> inspection = inspectionRepository.findByTaskId(taskId);
        int round = inspection.map(InspectionModel::round).orElse(1);
        boolean sampled = inspection.isPresent() && inspectionRepository.findDraws(taskId).stream()
                .anyMatch(d -> d.round() == round && d.contains(itemNo));
        if (passed) {
            if (inspection.isPresent()) {
                inspectionRepository.appendJudgement(ItemJudgementModel.pass(taskId, item.id(), itemNo, round,
                        sampled, clientId, now));
            }
            taskItemAppService.settleAccepted(task, item.accept());
            log.info("Task {}: reviewer passed Item {} ({})", taskId, itemNo, sampled ? "sampled" : "extra check");
        } else {
            if (inspection.isEmpty()) {
                inspectionRepository.save(InspectionModel.open(taskId, InspectionLevel.DEFAULT));
            }
            inspectionRepository.appendJudgement(ItemJudgementModel.fail(taskId, item.id(), itemNo, round, sampled,
                    reasonCategory, reason, clientId, now));
            taskItemRepository.update(item.rejectByReviewer());
            openRejection(task, item, reasonCategory, reason, now);
            log.info("Task {}: reviewer rejected Item {} ({}, {})", taskId, itemNo, reasonCategory,
                    sampled ? "sampled" : "extra check");
        }
        advance(taskRepository.findById(taskId).orElseThrow());
    }

    @Override
    public void waive(UUID taskId) {
        inspectionRepository.findByTaskId(taskId).ifPresent(i -> inspectionRepository.save(i.waive()));
    }

    /**
     * Judges the current sample once every sampled Item has settled, and acts on the decision (D19).
     * Loops when a decision needs no new reviewer input (a second stage with nothing left to draw).
     */
    private void advance(TaskModel task) {
        UUID taskId = task.id();
        Optional<InspectionModel> found = inspectionRepository.findByTaskId(taskId);
        if (found.isEmpty() || !found.get().isSampling() || task.status() != TaskStatus.PENDING_REVIEW) {
            taskItemAppService.resolveIfAllSettled(task);
            return;
        }
        InspectionModel inspection = found.get();
        List<SampleDrawModel> roundDraws = inspectionRepository.findDraws(taskId).stream()
                .filter(d -> d.round() == inspection.round()).toList();
        SampleDrawModel current = roundDraws.stream().filter(d -> !d.isDecided()).findFirst().orElse(null);
        if (current == null) {
            taskItemAppService.resolveIfAllSettled(task);
            return;
        }
        TaskItems items = taskItemRepository.findByTaskId(taskId);
        Map<Integer, SampleItemOutcome> outcomes = outcomes(items);
        SampleTally stage = SampleTally.of(current.itemNos(), outcomes);
        if (!stage.complete()) {
            return; // a sampled Item still awaits the reviewer, or its rejection path
        }
        Set<Integer> sampledInRound = new LinkedHashSet<>();
        roundDraws.forEach(d -> sampledInRound.addAll(d.itemNos()));
        SampleTally cumulative = SampleTally.of(sampledInRound, outcomes);
        SamplingPlan plan = current.plan();
        SamplingDecision decision = SamplingDecision.after(plan, current.stage(), cumulative.defects(),
                cumulative.sampled(), stage.defects(), inspection.round());
        Instant now = clock.instant();
        if (decision.next() == SamplingDecision.Next.DRAW_SECOND) {
            List<Integer> candidates = items.sampleable(flaggedShareCap).stream().map(TaskItemModel::itemNo)
                    .filter(n -> !sampledInRound.contains(n)).toList();
            if (candidates.isEmpty()) {
                // The lot has nothing left to draw: the first sample was all of it, so judge it as both.
                decision = SamplingDecision.afterWholeLot(plan, cumulative.defects(), cumulative.sampled(),
                        inspection.round());
            } else {
                inspectionRepository.recordDecision(current.decide(stage.defects(), decision.next(), now));
                SampleDrawModel second = SampleDrawModel.draw(taskId, inspection.round(), 2, plan, seeds.nextLong(),
                        candidates, now);
                inspectionRepository.insertDraw(second);
                // The reviewer gets a fresh window for the second sample.
                taskRepository.stampReviewDeadline(taskId, reviewWindow.closesAtCountingBusinessDays(now));
                log.info("Task {}: first sample inconclusive ({} wrong of {}); second sample of {} drawn",
                        taskId, stage.defects(), stage.sampled(), second.itemNos().size());
                return;
            }
        }
        inspectionRepository.recordDecision(current.decide(stage.defects(), decision.next(), now));
        log.info("Task {} round {}: {} wrong of {} sampled -> {}", taskId, inspection.round(),
                cumulative.defects(), cumulative.sampled(), decision.next());
        switch (decision.next()) {
            case ACCEPT_LOT -> {
                inspectionRepository.save(inspection.acceptLot());
                taskItemAppService.acceptLot(task);
            }
            case REWORK -> rework(task, inspection, cumulative);
            case PRO_RATA -> settleProRata(task, inspection, decision.errorRate());
            default -> throw new IllegalStateException("unreachable: " + decision);
        }
    }

    private void rework(TaskModel task, InspectionModel inspection, SampleTally failed) {
        if (agentRepository.findCandidateByVersionId(task.agentVersionId()).isEmpty()) {
            // The Agent can no longer take work, so it cannot rework: settle on the round's own sample.
            log.info("Task {}: agent version {} cannot take the rework; settling pro rata now",
                    task.id(), task.agentVersionId());
            settleProRata(task, inspection, ProRata.errorRate(failed.defects(), failed.sampled()));
            return;
        }
        inspectionRepository.save(inspection.startRework());
        // A failed lot takes back every Item the sample stood for, flagged Items beyond the cap included.
        List<TaskItemModel> back = taskItemRepository.findByTaskId(task.id()).sampleable(flaggedShareCap).stream()
                .map(TaskItemModel::returnForRework).toList();
        taskItemRepository.updateAll(back);
        ReworkRequest request = new ReworkRequest(SAMPLE_REWORKS, SAMPLE_REWORKS, List.of(
                new ReworkRequest.FailedCheck("SAMPLE_REJECTED", failed.defects() + " of " + failed.sampled()
                        + " sampled Items were found wrong by the buyer's reviewer; every Item not yet accepted "
                        + "is sent back once, and the Task is sampled again when it returns")));
        if (!routingAppService.redispatchForRework(task.id(), task.agentVersionId(), request)) {
            throw new IllegalStateException("Task " + task.id() + ": the agent became unavailable mid-rework");
        }
        log.info("Task {}: sample failed; {} Items returned to the agent for the whole-Task rework",
                task.id(), back.size());
    }

    private void settleProRata(TaskModel task, InspectionModel inspection, BigDecimal errorRate) {
        inspectionRepository.save(inspection.settleProRata(errorRate));
        // Every unverified Item the sample stood for settles at the measured rate, flagged beyond the cap too.
        for (TaskItemModel item : taskItemRepository.findByTaskId(task.id()).sampleable(flaggedShareCap)) {
            Money paid = ProRata.paidFor(item.price(), errorRate);
            if (paid.equals(item.price())) {
                taskItemAppService.settleAccepted(task, item.accept());
            } else {
                taskItemAppService.settleProRata(task, item.settleProRata(paid));
            }
        }
        log.info("Task {}: resample failed; unverified Items settled at error rate {}", task.id(), errorRate);
        taskItemAppService.resolveIfAllSettled(task);
    }

    private static Map<Integer, SampleItemOutcome> outcomes(TaskItems items) {
        Map<Integer, SampleItemOutcome> outcomes = new HashMap<>();
        for (TaskItemModel item : items.all()) {
            outcomes.put(item.itemNo(), SampleItemOutcome.of(item.status()));
        }
        return outcomes;
    }

    // ---- an Item's rejection response path (D17') ----

    private void openRejection(TaskModel task, TaskItemModel item, RejectReason category, String reason, Instant now) {
        List<ItemRejectionModel> earlier = itemRejectionRepository.findByTaskId(task.id()).stream()
                .filter(r -> r.itemId().equals(item.id())).toList();
        boolean revisedBefore = earlier.stream().anyMatch(r -> r.round().response() == AgentResponse.REVISE);
        RejectionModel round = RejectionModel.open(task.id(), earlier.size() + 1, category, reason, !revisedBefore,
                now, itemWindows);
        itemRejectionRepository.save(new ItemRejectionModel(item.id(), item.itemNo(), round));
        // The notice goes out after commit, so an Agent never hears of a rejection that rolled back.
        eventPublisher.publishEvent(new ItemRejectedDomainEvent(task.id(), item.id()));
    }

    @Override
    public void respondToItemRejection(UUID taskId, int itemNo, String bearerToken,
                                       AgentRejectionResponseInfo response) {
        DispatchTokenClaims claims = dispatchTokenService.verify(bearerToken).requireTask(taskId);
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        claims.requireAssignedVersion(task.agentVersionId());
        requireOpen(task);
        TaskItemModel item = taskItemRepository.findByTaskId(taskId).item(itemNo);
        ItemRejectionModel rejection = itemRejectionRepository.findLatest(item.id())
                .filter(r -> item.status() == ItemStatus.REJECTED)
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Item " + itemNo + " of task " + taskId + " is not awaiting a response to a rejection"));
        if (response.action() == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A response action is required");
        }
        RejectionModel responded = rejection.round().respond(response.action(), response.reason(), clock.instant(),
                itemWindows);
        switch (response.action()) {
            case REVISE -> {
                boolean noPayload = response.resultPayloadJson() == null || response.resultPayloadJson().isBlank();
                boolean noUrl = response.resultUrl() == null || response.resultUrl().isBlank();
                if (noPayload && noUrl) {
                    throw new DomainException(ResultCode.VALIDATION_ERROR,
                            "A revision needs the revised result: resultPayloadJson or resultUrl");
                }
                itemRejectionRepository.save(rejection.withRound(responded));
                itemRejectionRepository.recordReplacedResult(responded.id(), item.resultPayload(), item.resultUrl());
                TaskItemModel revised = taskItemAppService.reviseRejected(task, item,
                        noPayload ? "" : response.resultPayloadJson(), noUrl ? null : response.resultUrl(),
                        responded.round());
                log.info("Task {}: the agent revised Item {} -> {}", taskId, itemNo, revised.status());
            }
            case CONCEDE -> {
                itemRejectionRepository.save(rejection.withRound(responded));
                taskItemAppService.settleRefunded(task, item.concede());
                log.info("Task {}: the agent conceded Item {}; refunded", taskId, itemNo);
            }
            case STAND_BY -> {
                itemRejectionRepository.save(rejection.withRound(responded));
                log.info("Task {}: the agent stood by Item {}; the buyer decides until {}", taskId, itemNo,
                        responded.decisionDeadline());
            }
        }
        advance(task);
    }

    @Override
    public void decideItem(UUID taskId, UUID clientId, int itemNo, ClientDecision decision) {
        if (decision == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "decision: ACCEPT or ARBITRATE");
        }
        TaskModel task = loadOwned(taskId, clientId);
        requireOpen(task);
        TaskItemModel item = taskItemRepository.findByTaskId(taskId).item(itemNo);
        ItemRejectionModel rejection = itemRejectionRepository.findLatest(item.id())
                .filter(r -> item.status() == ItemStatus.REJECTED && r.round().isAwaitingDecision())
                .orElseThrow(() -> new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                        "Item " + itemNo + " is not waiting for your decision on a result the agent stood by"));
        if (decision == ClientDecision.ARBITRATE) {
            // Arbitration is opened per Task today; per Item it is a Known gap (build-status).
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "Arbitration of a single Item is not "
                    + "available yet. Accept the Item, or let the decision window close (which accepts it)");
        }
        Instant now = clock.instant();
        RejectionModel round = rejection.round();
        boolean silently = round.decisionWindowClosed(now);
        itemRejectionRepository.save(rejection.withRound(silently ? round.acceptSilently(now)
                : round.decide(ClientDecision.ACCEPT, now)));
        taskItemAppService.settleAccepted(task, item.acceptAfterStandBy(silently));
        log.info("Task {}: buyer accepted Item {} after the agent stood by it", taskId, itemNo);
        advance(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> itemResponseExpiredIds() {
        return itemRejectionRepository.findItemIdsResponseExpired(clock.instant());
    }

    @Override
    public void standBySilentlyItem(UUID itemId) {
        withOpenRejection(itemId, (task, item, rejection) -> {
            Instant now = clock.instant();
            if (!rejection.round().isAwaitingResponse() || !rejection.round().responseWindowClosed(now)) {
                return;
            }
            itemRejectionRepository.save(rejection.withRound(rejection.round().standBySilently(now, itemWindows)));
            log.info("Task {}: no response from the agent on Item {}; counted as standing by", task.id(),
                    item.itemNo());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> itemDecisionExpiredIds() {
        return itemRejectionRepository.findItemIdsDecisionExpired(clock.instant());
    }

    @Override
    public void acceptSilentlyItem(UUID itemId) {
        withOpenRejection(itemId, (task, item, rejection) -> {
            Instant now = clock.instant();
            if (!rejection.round().isAwaitingDecision() || !rejection.round().decisionWindowClosed(now)) {
                return;
            }
            itemRejectionRepository.save(rejection.withRound(rejection.round().acceptSilently(now)));
            taskItemAppService.settleAccepted(task, item.acceptAfterStandBy(true));
            log.info("Task {}: no decision on Item {}; the stood-by result is accepted", task.id(), item.itemNo());
            advance(task);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> itemNoticePendingIds() {
        return itemRejectionRepository.findItemIdsNoticePending(clock.instant(), MAX_NOTICE_ATTEMPTS);
    }

    @Override
    public void deliverItemNotice(UUID itemId) {
        TaskItemModel item = taskItemRepository.findById(itemId).orElse(null);
        if (item == null || item.status() != ItemStatus.REJECTED) {
            return;
        }
        TaskModel task = taskRepository.findById(item.taskId()).orElse(null);
        ItemRejectionModel rejection = itemRejectionRepository.findLatest(itemId).orElse(null);
        Instant now = clock.instant();
        if (task == null || task.status().isClosed() || rejection == null || !rejection.round().isAwaitingResponse()
                || rejection.round().responseWindowClosed(now)) {
            return;
        }
        RejectionModel round = rejection.round();
        String webhookUrl = agentRepository.findWebhookUrlByVersionId(task.agentVersionId()).orElse(null);
        if (webhookUrl == null) {
            itemRejectionRepository.recordNoticeAttempt(round.id(), null);
            return;
        }
        String token = dispatchTokenService.issue(task.id(), task.agentVersionId(), round.responseDeadline());
        String secret = agentRepository.findSigningSecretByVersionId(task.agentVersionId()).orElse(null);
        RejectionNotice notice = new RejectionNotice(task.id(), task.agentVersionId(), round.round(),
                round.reasonCategory().name(), round.reason(), round.responseDeadline(), round.revisionAllowed(),
                publicBaseUrl + "/api/agent-callbacks/" + task.id() + "/items/" + item.itemNo() + "/rejection-response",
                task.title(), task.description(), outputSpecJsonMapper.toJson(task.outputDescription()),
                item.itemNo(), item.input(), item.resultPayload(), acceptanceJsonMapper.toJson(task.contract()));
        boolean delivered = agentNoticePort.sendRejectionNotice(webhookUrl, token, secret, notice);
        itemRejectionRepository.recordNoticeAttempt(round.id(), delivered ? now : null);
        if (delivered) {
            log.info("Task {}: rejection notice for Item {} (round {}) delivered", task.id(), item.itemNo(),
                    round.round());
        }
    }

    // ---- reads ----

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionView> inspectionFor(UUID taskId, UUID clientId) {
        taskRepository.findById(taskId).filter(t -> t.clientId().equals(clientId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        return inspectionRepository.findByTaskId(taskId).map(i -> new InspectionView(i,
                inspectionRepository.findDraws(taskId), inspectionRepository.findJudgements(taskId),
                itemRejectionRepository.findByTaskId(taskId)));
    }

    @Override
    @Transactional(readOnly = true)
    public SourceSnapshotAppService.Download sourceFor(UUID taskId, UUID clientId, int itemNo) {
        taskRepository.findById(taskId).filter(t -> t.clientId().equals(clientId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        TaskItemModel item = taskItemRepository.findByTaskId(taskId).item(itemNo);
        if (item.sourceSnapshotId() == null) {
            throw new DomainException(ResultCode.NOT_FOUND, "Item " + itemNo + "'s input is text, not a document");
        }
        return sourceSnapshotAppService.contentOf(item.sourceSnapshotId());
    }

    // ---- helpers ----

    private interface OpenRejectionAction {
        void apply(TaskModel task, TaskItemModel item, ItemRejectionModel rejection);
    }

    /** Runs {@code action} under the Task's row lock when the Item is still on its rejection path. */
    private void withOpenRejection(UUID itemId, OpenRejectionAction action) {
        TaskItemModel unlocked = taskItemRepository.findById(itemId).orElse(null);
        if (unlocked == null) {
            return;
        }
        TaskModel task = taskRepository.findByIdForUpdate(unlocked.taskId()).orElse(null);
        if (task == null || task.status().isClosed()) {
            return;
        }
        TaskItemModel item = taskItemRepository.findById(itemId).orElse(null);
        if (item == null || item.status() != ItemStatus.REJECTED) {
            return; // responded or decided since the id was read
        }
        itemRejectionRepository.findLatest(itemId).ifPresent(r -> action.apply(task, item, r));
    }

    private static void requireOpen(TaskModel task) {
        if (task.status().isClosed()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + task.id() + " is " + task.status() + "; its Items can no longer change");
        }
    }

    /** Ownership check (Invariant #5): a foreign task is indistinguishable from a missing one. Row-locked. */
    private TaskModel loadOwned(UUID taskId, UUID clientId) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        if (!task.clientId().equals(clientId)) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId);
        }
        return task;
    }

}
