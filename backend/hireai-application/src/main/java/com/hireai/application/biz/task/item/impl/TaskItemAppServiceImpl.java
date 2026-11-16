package com.hireai.application.biz.task.item.impl;

import com.hireai.application.biz.acceptance.delivery.DeliverySnapshotAppService;
import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.domain.biz.acceptance.delivery.model.DeliveryOutcome;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.item.TaskItemAppService;
import com.hireai.application.biz.task.routing.RoutingAppService;
import com.hireai.domain.biz.task.routing.info.ReworkRequest;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.acceptance.check.model.AcceptanceEvidence;
import com.hireai.domain.biz.acceptance.check.model.FindingFacts;
import com.hireai.domain.biz.acceptance.check.model.InvoiceReading;
import com.hireai.domain.biz.acceptance.check.model.SelfReportedAnomaly;
import com.hireai.domain.biz.acceptance.check.service.InvoiceReader;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.adjudication.model.ReworkPolicy;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.info.AgentItemResultInfo;
import com.hireai.domain.biz.task.item.model.ItemAcceptance;
import com.hireai.domain.biz.task.item.model.ItemCheck;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.info.ItemInput;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Thin orchestration of the multi-Item flow: the rules (numbering, state machine, accepted
 * fraction, business-day window) live in {@link TaskItems}, {@link TaskItemModel} and
 * {@link ReviewWindow}; the money arithmetic in the settlement domain service.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class TaskItemAppServiceImpl implements TaskItemAppService {

    private static final int FIRST_ATTEMPT = 1;
    /** The Task-level rework reason when some of its Items come back for rework (spec S5). */
    public static final String ITEM_CHECKS_FAILED = "ITEM_CHECKS_FAILED";

    private final TaskItemRepository taskItemRepository;
    private final TaskRepository taskRepository;
    private final ValidationDomainService validationDomainService;
    private final SettlementWriteAppService settlementWriteAppService;
    private final ReputationWriteAppService reputationWriteAppService;
    private final WebhookOutboxAppService webhookOutboxAppService;
    private final AgentRepository agentRepository;
    private final ApiKeyTaskRepository apiKeyTaskRepository;
    private final ReviewWindow reviewWindow;
    private final Clock clock;
    /** Freezes an Item's file deliverable before its checks run (D24), keyed by the Item's id. */
    private final DeliverySnapshotAppService deliverySnapshotAppService;
    /** Reads an invoice result for the ap_invoice_extraction checks (#106). */
    private final InvoiceReader invoiceReader;
    /** How many times a rejected Item may come back (spec S5, {@code hireai.acceptance.max-reworks}). */
    private final ReworkPolicy reworkPolicy;
    /** Closes an inspection left open when the Task ends with nothing to sample. */
    private final InspectionRepository inspectionRepository;
    /** Looked up lazily: routing depends on the task write service, which depends on this one. */
    private final ObjectProvider<RoutingAppService> routingAppService;

    @Override
    public void createForSubmit(UUID taskId, List<ItemInput> inputs, Money price) {
        taskItemRepository.insertAll(TaskItems.submitInputs(taskId, inputs, price));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasItems(UUID taskId) {
        return taskItemRepository.countByTaskId(taskId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public TaskItems itemsOf(UUID taskId) {
        return taskItemRepository.findByTaskId(taskId);
    }

    @Override
    public TaskModel recordItemResults(TaskModel inFlight, AgentResultInfo result) {
        TaskItems items = taskItemRepository.findByTaskId(inFlight.id());
        Map<Integer, AgentItemResultInfo> byItemNo = indexByItemNo(result.items(), items);
        // A Task that already had a result is back from its whole-Task rework (spec S8): each reworked
        // Item's deliverable is a new attempt and gets its own frozen copy.
        boolean reworked = inFlight.result() != null;

        TaskModel recorded = inFlight.recordResult(TaskResultModel.record(inFlight.id(), result.agentStatus(),
                result.resultPayloadJson(), result.resultUrl()));
        taskRepository.save(recorded);

        Contract contract = recorded.contract();
        int maxReworks = reworkPolicy.maxReworks();
        Boolean agentCanRework = null; // asked once, only when an Item needs it
        List<TaskItemModel> judged = new ArrayList<>(items.count());
        List<TaskItemModel> reworking = new ArrayList<>();
        for (TaskItemModel item : items.all()) {
            if (item.status() != ItemStatus.AWAITING_RESULT) {
                // Settled, or on its own rejection path, before a whole-Task rework (spec S8): a rework
                // re-sends only the Items still awaiting a result, and only those take a new one.
                judged.add(item);
                continue;
            }
            AgentItemResultInfo itemResult = byItemNo.get(item.itemNo());
            TaskItemModel next = itemResult == null || !"COMPLETED".equalsIgnoreCase(itemResult.agentStatus())
                    ? item.recordFailure(itemResult == null ? "MISSING" : itemResult.agentStatus())
                    : item.recordResult(itemResult.agentStatus().trim(), payloadOf(itemResult.resultPayloadJson()),
                            itemResult.resultUrl(), acceptanceFor(recorded, item,
                                    attemptId(item, reworked ? "rework-" + recorded.result().id() : null),
                                    itemResult.agentStatus().trim(), itemResult.resultPayloadJson(),
                                    itemResult.resultUrl(), contract, itemResult.anomalies()));
            if (next.reworkAllowed(maxReworks)) {
                if (agentCanRework == null) {
                    agentCanRework = agentRepository.findCandidateByVersionId(recorded.agentVersionId()).isPresent();
                }
                if (agentCanRework) {
                    // A deterministic rejection goes back to the same Agent with its failing checks (spec S5),
                    // up to maxReworks times; its escrow stays held meanwhile.
                    next = next.returnForItemRework(maxReworks);
                    reworking.add(next);
                }
            }
            taskItemRepository.update(next);
            if (next.status() == ItemStatus.SPEC_VIOLATION || next.status() == ItemStatus.FAILED) {
                // Refunded at once (reworks used up, or the Agent said it failed): one bad Item never holds
                // the others back (D14).
                settlementWriteAppService.settleItemRefunded(next.taskId(), next.id(), next.itemNo(),
                        recorded.clientId(), next.price());
            }
            judged.add(next);
        }
        if (!reworking.isEmpty()) {
            return returnForItemRework(recorded, reworking, maxReworks);
        }
        TaskItems outcome = new TaskItems(judged);

        if (!outcome.anyPassed() && !outcome.anyOpenOrPaid()) {
            TaskModel failed = recorded.failValidation(); // every Item already refunded on its own
            taskRepository.save(failed);
            closeInspection(failed.id()); // e.g. every reworked Item failed again: nothing is left to sample
            recordReputation(failed, outcome);
            webhookOutboxAppService.enqueueFailed(failed, failed.status().name());
            log.info("Task {}: none of its {} Items passed -> SPEC_VIOLATION (each Item refunded)",
                    failed.id(), outcome.count());
            return failed;
        }

        TaskModel gated = recorded.passValidation();
        taskRepository.save(gated);
        if (apiKeyTaskRepository.findApiKeyIdByTask(gated.id()).isPresent()) {
            // Programmatic channel: passing Items settle at once, deterministically, as single Tasks do. An
            // Item a check sent to review is paid only when the Client confirms it (D7), so it waits.
            TaskModel settled = acceptAndResolve(gated, outcome, true, true);
            if (settled.status() == TaskStatus.PENDING_REVIEW) {
                taskRepository.stampReviewDeadline(gated.id(), reviewWindow.closesAtCountingBusinessDays(clock.instant()));
            }
            return settled;
        }
        // Web channel: multi-Item Tasks count their review window in business days (spec S7/D10).
        Instant reviewDeadline = reviewWindow.closesAtCountingBusinessDays(clock.instant());
        taskRepository.stampReviewDeadline(gated.id(), reviewDeadline);
        log.info("Task {}: {} of {} Items await review (window closes {})", gated.id(),
                outcome.awaitingReview().size(), outcome.count(), reviewDeadline);
        return gated;
    }

    /**
     * Sends the Task back to its Agent for the Items a check rejected (spec S5): the WorkOrder carries only
     * Items still awaiting a result, each with the checks it failed. The Items that passed wait meanwhile, so
     * the review window, sampling and the API channel's settlement all start once every rework has come back.
     */
    private TaskModel returnForItemRework(TaskModel recorded, List<TaskItemModel> reworking, int maxReworks) {
        int reworkNo = reworking.stream().mapToInt(TaskItemModel::reworks).max().orElse(1);
        String itemNos = String.join(", ", reworking.stream().map(i -> String.valueOf(i.itemNo())).toList());
        ReworkRequest request = new ReworkRequest(reworkNo, maxReworks, List.of(new ReworkRequest.FailedCheck(
                ITEM_CHECKS_FAILED, "Item(s) " + itemNos + " failed their checks and come back for rework; each "
                        + "carries the checks it failed. Answer only the Items in this WorkOrder.")));
        if (!routingAppService.getObject().redispatchForRework(recorded.id(), recorded.agentVersionId(), request)) {
            throw new IllegalStateException("Task " + recorded.id() + ": the agent became unavailable mid-rework");
        }
        log.info("Task {}: Items {} failed their checks -> back to the agent for rework (up to {})",
                recorded.id(), itemNos, maxReworks);
        return taskRepository.findById(recorded.id()).orElseThrow();
    }

    @Override
    public void acceptItem(UUID taskId, UUID clientId, int itemNo) {
        TaskModel task = taskRepository.findByIdForUpdate(taskId)
                .filter(t -> t.clientId().equals(clientId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND, "Task not found: " + taskId));
        TaskItems items = taskItemRepository.findByTaskId(taskId);
        if (items.isEmpty()) {
            throw new DomainException(ResultCode.NOT_FOUND, "Task " + taskId + " has no Items");
        }
        if (task.status() != TaskStatus.PENDING_REVIEW) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " is " + task.status() + "; its Items are no longer awaiting review");
        }
        TaskItemModel accepted = items.item(itemNo).accept();
        settleAccepted(task, accepted);
        TaskItems after = replace(items, accepted);
        if (after.allSettled()) {
            resolve(task, after);
        }
        log.info("Task {}: Item {} accepted by client {}", taskId, itemNo, clientId);
    }

    @Override
    public void acceptAllAwaitingReview(TaskModel pendingReview, boolean automatically) {
        acceptAndResolve(pendingReview, taskItemRepository.findByTaskId(pendingReview.id()), automatically, false);
    }

    @Override
    public void acceptLot(TaskModel pendingReview) {
        acceptAndResolve(pendingReview, taskItemRepository.findByTaskId(pendingReview.id()), false, true);
    }

    /**
     * Accepts the Items awaiting review ({@code lotOnly}: the lot only, never an Item a check flagged, D7).
     * When the review window closes every Item still awaiting review is accepted, a flagged one too: no
     * refund unless the Agent is shown wrong (owner decision on #87, revised 2026-11-16).
     */
    private TaskModel acceptAndResolve(TaskModel pendingReview, TaskItems items, boolean automatically,
                                       boolean lotOnly) {
        TaskItems after = items;
        UUID builderId = builderOf(pendingReview);
        List<TaskItemModel> accepted = new ArrayList<>();
        for (TaskItemModel item : lotOnly ? items.lot() : items.awaitingReview()) {
            TaskItemModel next = automatically ? item.acceptAutomatically() : item.accept();
            settlementWriteAppService.settleItemAccepted(pendingReview.id(), next.id(), next.itemNo(),
                    pendingReview.clientId(), builderId, next.price());
            accepted.add(next);
        }
        taskItemRepository.updateAll(accepted);
        for (TaskItemModel next : accepted) {
            after = replace(after, next);
        }
        // An Item on its own rejection path (spec S8, D17') settles when that path does.
        return after.allSettled() ? resolve(pendingReview, after) : pendingReview;
    }

    @Override
    public void settleAccepted(TaskModel task, TaskItemModel accepted) {
        taskItemRepository.update(accepted);
        settlementWriteAppService.settleItemAccepted(task.id(), accepted.id(), accepted.itemNo(),
                task.clientId(), builderOf(task), accepted.price());
    }

    @Override
    public void settleRefunded(TaskModel task, TaskItemModel refunded) {
        taskItemRepository.update(refunded);
        settlementWriteAppService.settleItemRefunded(task.id(), refunded.id(), refunded.itemNo(),
                task.clientId(), refunded.price());
    }

    @Override
    public void settleProRata(TaskModel task, TaskItemModel proRata) {
        taskItemRepository.update(proRata);
        if (!proRata.paidAmount().isPositive()) { // an error rate of 1: nothing to pay
            settlementWriteAppService.settleItemRefunded(task.id(), proRata.id(), proRata.itemNo(), task.clientId(),
                    proRata.price());
            return;
        }
        settlementWriteAppService.settleItemProRata(task.id(), proRata.id(), proRata.itemNo(), task.clientId(),
                builderOf(task), proRata.price(), proRata.paidAmount());
    }

    @Override
    public TaskModel resolveIfAllSettled(TaskModel task) {
        if (task.status() != TaskStatus.PENDING_REVIEW) {
            return task;
        }
        TaskItems items = taskItemRepository.findByTaskId(task.id());
        return items.allSettled() ? resolve(task, items) : task;
    }

    @Override
    public void closeInspection(UUID taskId) {
        inspectionRepository.findByTaskId(taskId).filter(i -> !i.status().isFinished())
                .ifPresent(i -> inspectionRepository.save(i.close()));
    }

    /** Every Item has settled: the Task resolves and writes its one reputation event (D22, D27). */
    private TaskModel resolve(TaskModel pendingReview, TaskItems settled) {
        TaskModel resolved = settled.anyPaid() ? pendingReview.resolveItems(settled.allAccepted())
                : pendingReview.resolveItemsAllRefunded();
        taskRepository.save(resolved);
        recordReputation(resolved, settled);
        if (apiKeyTaskRepository.findApiKeyIdByTask(resolved.id()).isPresent()) {
            webhookOutboxAppService.enqueueCompleted(resolved);
        }
        log.info("Task {} resolved {}: {} of {} Items accepted", resolved.id(), resolved.resolution(),
                settled.acceptedCount(), settled.count());
        return resolved;
    }

    private void recordReputation(TaskModel task, TaskItems settled) {
        reputationWriteAppService.recordItemsOutcome(task.id(), task.agentVersionId(), task.clientId(),
                settled.creditedCount(), settled.count());
    }

    @Override
    public TaskItemModel reviseRejected(TaskModel task, TaskItemModel rejected, String payload, String url,
                                        int round) {
        Contract contract = task.contract();
        TaskItemModel.requireHttpsResultUrl(rejected.itemNo(), url);
        ItemAcceptance acceptance = acceptanceFor(task, rejected, attemptId(rejected, "revision-" + round),
                "COMPLETED", payload, url, contract, List.of());
        TaskItemModel revised = rejected.revise(payloadOf(payload), url, acceptance);
        if (revised.status() == ItemStatus.SPEC_VIOLATION) {
            settleRefunded(task, revised); // a revision that fails the Contract's checks is refunded (D17)
        } else {
            taskItemRepository.update(revised);
        }
        return revised;
    }

    private static String payloadOf(String payload) {
        return payload == null ? "" : payload;
    }

    /**
     * The id an Item's result is judged under: the Item's own id for its first result (so a file
     * deliverable gets its own DeliverySnapshot, one per Item, and every later step judges that frozen
     * copy), and a stable id per later attempt (the whole-Task rework, each revision).
     */
    private static UUID attemptId(TaskItemModel item, String attempt) {
        return attempt == null ? item.id()
                : UUID.nameUUIDFromBytes((item.id() + "#" + attempt).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Runs the Contract's checks on one Item's result (spec S5): fetches and freezes a file first (D24),
     * reads an invoice (#106), gathers the client's own earlier Items for the duplicate Findings (D45),
     * and keeps the Findings and the Agent's anomalies beside the checks.
     */
    private ItemAcceptance acceptanceFor(TaskModel task, TaskItemModel item, UUID resultId, String agentStatus,
                                         String payload, String url, Contract contract,
                                         List<SelfReportedAnomaly> anomalies) {
        TaskResultModel asResult = TaskResultModel.rehydrate(resultId, item.taskId(), agentStatus,
                payloadOf(payload), url, clock.instant());
        DeliveryOutcome delivery = validationDomainService.needsDelivery(contract, asResult)
                ? deliverySnapshotAppService.capture(contract, asResult, item.id())
                : null;
        InvoiceReading invoice = validationDomainService.needsInvoice(contract) ? invoiceReader.read(payload) : null;
        FindingFacts facts = FindingFacts.NONE;
        if (invoice != null) {
            String key = invoice instanceof InvoiceReading.Read read ? read.invoice().duplicateKey().orElse(null) : null;
            facts = new FindingFacts(List.of(), taskItemRepository.findEarlierItemsWithSameDocument(item, task.clientId()),
                    key == null ? List.of() : taskItemRepository.findOtherItemsWithInvoiceKey(task.clientId(), key, item.id()));
            taskItemRepository.recordInvoiceKey(item, task.clientId(), key);
        }
        ValidationReportModel report = validationDomainService.judge(contract, asResult,
                new AcceptanceEvidence(delivery, null, anomalies, facts, invoice), FIRST_ATTEMPT);
        return ItemAcceptance.fromReport(report);
    }

    /** Refuses (400) a callback naming an Item twice or an Item the Task does not have. */
    private static Map<Integer, AgentItemResultInfo> indexByItemNo(List<AgentItemResultInfo> results,
                                                                   TaskItems items) {
        Map<Integer, AgentItemResultInfo> byItemNo = new HashMap<>();
        TreeSet<Integer> unknown = new TreeSet<>();
        for (AgentItemResultInfo r : results) {
            if (byItemNo.putIfAbsent(r.itemNo(), r) != null) {
                throw new DomainException(ResultCode.VALIDATION_ERROR,
                        "items: Item " + r.itemNo() + " appears more than once");
            }
            if (r.itemNo() < 1 || r.itemNo() > items.count()) {
                unknown.add(r.itemNo());
            }
            // Before anything is recorded, so a bad link refuses the whole callback (400) and it can be resent.
            TaskItemModel.requireHttpsResultUrl(r.itemNo(), r.resultUrl());
        }
        if (!unknown.isEmpty()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "items: this Task has Items 1 to " + items.count() + "; got " + unknown);
        }
        return byItemNo;
    }

    private static TaskItems replace(TaskItems items, TaskItemModel changed) {
        List<TaskItemModel> next = items.all().stream()
                .map(i -> i.itemNo() == changed.itemNo() ? changed : i)
                .toList();
        return new TaskItems(next);
    }

    private UUID builderOf(TaskModel task) {
        return agentRepository.findOwnerByVersionId(task.agentVersionId())
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "No agent owner for version " + task.agentVersionId()));
    }
}
