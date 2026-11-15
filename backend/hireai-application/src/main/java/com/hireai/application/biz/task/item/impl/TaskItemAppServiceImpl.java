package com.hireai.application.biz.task.item.impl;

import com.hireai.application.biz.ledger.settlement.SettlementWriteAppService;
import com.hireai.application.biz.reputation.ReputationWriteAppService;
import com.hireai.application.biz.task.item.TaskItemAppService;
import com.hireai.application.biz.task.webhookdelivery.WebhookOutboxAppService;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.service.ValidationDomainService;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.domain.biz.task.idempotency.repository.ApiKeyTaskRepository;
import com.hireai.domain.biz.task.info.AgentResultInfo;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.item.info.AgentItemResultInfo;
import com.hireai.domain.biz.task.item.model.ItemCheck;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.domain.biz.task.model.ReviewWindow;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @Override
    public void createForSubmit(UUID taskId, List<String> inputs, Money price) {
        taskItemRepository.insertAll(TaskItems.submit(taskId, inputs, price));
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

        TaskModel recorded = inFlight.recordResult(TaskResultModel.record(inFlight.id(), result.agentStatus(),
                result.resultPayloadJson(), result.resultUrl()));
        taskRepository.save(recorded);

        Contract contract = recorded.contract() != null ? recorded.contract() : recorded.outputSpec().toContract();
        List<TaskItemModel> judged = new ArrayList<>(items.count());
        for (TaskItemModel item : items.all()) {
            TaskItemModel next = judge(item, byItemNo.get(item.itemNo()), contract);
            taskItemRepository.update(next);
            if (next.status() == ItemStatus.SPEC_VIOLATION || next.status() == ItemStatus.FAILED) {
                // Refunded at once: one bad Item never holds the others back (D14).
                settlementWriteAppService.settleItemRefunded(next.taskId(), next.id(), next.itemNo(),
                        recorded.clientId(), next.price());
            }
            judged.add(next);
        }
        TaskItems outcome = new TaskItems(judged);

        if (!outcome.anyPassed()) {
            TaskModel failed = recorded.failValidation(); // every Item already refunded on its own
            taskRepository.save(failed);
            recordReputation(failed, outcome);
            webhookOutboxAppService.enqueueFailed(failed, failed.status().name());
            log.info("Task {}: none of its {} Items passed -> SPEC_VIOLATION (each Item refunded)",
                    failed.id(), outcome.count());
            return failed;
        }

        TaskModel gated = recorded.passValidation();
        taskRepository.save(gated);
        if (apiKeyTaskRepository.findApiKeyIdByTask(gated.id()).isPresent()) {
            // Programmatic channel: passing Items settle at once, deterministically, as single Tasks do.
            return acceptAndResolve(gated, outcome, true);
        }
        // Web channel: multi-Item Tasks count their review window in business days (spec S7/D10).
        Instant reviewDeadline = reviewWindow.closesAtCountingBusinessDays(clock.instant());
        taskRepository.stampReviewDeadline(gated.id(), reviewDeadline);
        log.info("Task {}: {} of {} Items await review (window closes {})", gated.id(),
                outcome.awaitingReview().size(), outcome.count(), reviewDeadline);
        return gated;
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
        acceptAndResolve(pendingReview, taskItemRepository.findByTaskId(pendingReview.id()), automatically);
    }

    private TaskModel acceptAndResolve(TaskModel pendingReview, TaskItems items, boolean automatically) {
        TaskItems after = items;
        for (TaskItemModel item : items.awaitingReview()) {
            TaskItemModel accepted = automatically ? item.acceptAutomatically() : item.accept();
            settleAccepted(pendingReview, accepted);
            after = replace(after, accepted);
        }
        return resolve(pendingReview, after);
    }

    private void settleAccepted(TaskModel task, TaskItemModel accepted) {
        taskItemRepository.update(accepted);
        settlementWriteAppService.settleItemAccepted(task.id(), accepted.id(), accepted.itemNo(),
                task.clientId(), builderOf(task), accepted.price());
    }

    /** Every Item has settled: the Task resolves and writes its one reputation event (D22, D27). */
    private TaskModel resolve(TaskModel pendingReview, TaskItems settled) {
        TaskModel resolved = pendingReview.resolveItems(settled.allAccepted());
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
                settled.acceptedCount(), settled.count());
    }

    private TaskItemModel judge(TaskItemModel item, AgentItemResultInfo itemResult, Contract contract) {
        if (itemResult == null || !"COMPLETED".equalsIgnoreCase(itemResult.agentStatus())) {
            return item.recordFailure(itemResult == null ? "MISSING" : itemResult.agentStatus());
        }
        String payload = itemResult.resultPayloadJson() == null ? "" : itemResult.resultPayloadJson();
        ValidationReportModel report = validationDomainService.validate(contract,
                TaskResultModel.record(item.taskId(), itemResult.agentStatus(), payload, itemResult.resultUrl()),
                FIRST_ATTEMPT);
        List<ItemCheck> checks = report.checks().stream()
                .map(c -> new ItemCheck(c.rule(), c.passed(), c.detail()))
                .toList();
        return item.recordResult(itemResult.agentStatus().trim(), payload, itemResult.resultUrl(), checks);
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
