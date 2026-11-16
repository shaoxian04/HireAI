package com.hireai.application.biz.acceptance.receipt.impl;

import com.hireai.application.biz.acceptance.receipt.ReceiptAppService;
import com.hireai.application.port.query.ReceiptEvidenceQueryPort;
import com.hireai.application.port.query.ReceiptEvidenceQueryPort.SettledSubject;
import com.hireai.application.port.receipt.ReceiptSigner;
import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;
import com.hireai.domain.biz.acceptance.receipt.repository.ReceiptRepository;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.ItemSettlementRepository;
import com.hireai.domain.biz.ledger.settlement.repository.SettlementRepository;
import com.hireai.domain.biz.offering.agent.repository.AgentRepository;
import com.hireai.domain.biz.task.item.model.TaskItems;
import com.hireai.domain.biz.task.item.repository.TaskItemRepository;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.repository.TaskRepository;
import com.hireai.utility.canonical.JsonCanonicalizer;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptAppServiceImpl implements ReceiptAppService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptEvidenceQueryPort evidence;
    private final ReceiptSigner signer;
    private final ReceiptBodyAssembler assembler;
    private final TaskRepository taskRepository;
    private final TaskItemRepository taskItemRepository;
    private final SettlementRepository settlementRepository;
    private final ItemSettlementRepository itemSettlementRepository;
    private final AgentRepository agentRepository;
    private final Clock clock;
    /** A sampled Task's Item receipts wait for its inspection to end (spec S8, D6). */
    private final InspectionRepository inspectionRepository;

    @Override
    @Transactional
    public List<ReceiptModel> receiptsForTask(UUID taskId, Reader reader) {
        TaskModel task = taskRepository.findById(taskId).orElseThrow(() -> notFound("task", taskId));
        Optional<UUID> builderId = builderOf(task);
        requireParty(task, builderId, reader, "task", taskId);

        TaskItems items = taskItemRepository.findByTaskId(taskId);
        settlementRepository.findByTaskId(taskId).ifPresent(s -> issueQuietly(task, builderId, items, s, null));
        for (ItemSettlementModel s : itemSettlementRepository.findByTaskId(taskId)) {
            issueQuietly(task, builderId, items, null, s);
        }
        return receiptRepository.findByTaskId(taskId);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptModel receipt(UUID receiptId, Reader reader) {
        ReceiptModel receipt = receiptRepository.findById(receiptId).orElseThrow(() -> notFound("receipt", receiptId));
        TaskModel task = taskRepository.findById(receipt.taskId()).orElseThrow(() -> notFound("receipt", receiptId));
        requireParty(task, builderOf(task), reader, "receipt", receiptId);
        return receipt;
    }

    @Override
    @Transactional
    public int issuePending(int limit) {
        int issued = 0;
        for (SettledSubject subject : evidence.settledWithoutReceipt(limit)) {
            TaskModel task = taskRepository.findById(subject.taskId()).orElse(null);
            if (task == null) {
                continue;
            }
            TaskItems items = taskItemRepository.findByTaskId(task.id());
            Optional<UUID> builderId = builderOf(task);
            if (subject.itemNo() == null) {
                Optional<SettlementModel> s = settlementRepository.findByTaskId(task.id());
                if (s.isPresent() && issueQuietly(task, builderId, items, s.get(), null)) {
                    issued++;
                }
            } else {
                Optional<ItemSettlementModel> s = itemSettlementRepository.findByTaskId(task.id()).stream()
                        .filter(is -> is.itemNo() == subject.itemNo()).findFirst();
                if (s.isPresent() && issueQuietly(task, builderId, items, null, s.get())) {
                    issued++;
                }
            }
        }
        return issued;
    }

    @Override
    public List<ReceiptSigner.PublicKey> publishedKeys() {
        return signer.publishedKeys();
    }

    /**
     * Issues the subject's receipt unless it has one. A failure is logged, never thrown: a receipt
     * gates nothing, so it must not fail the read (or the sweep) that asked for it.
     */
    private boolean issueQuietly(TaskModel task, Optional<UUID> builderId, TaskItems items,
                                 SettlementModel settlement, ItemSettlementModel itemSettlement) {
        Integer itemNo = itemSettlement == null ? null : itemSettlement.itemNo();
        if (itemNo != null && inspectionOpen(task)) {
            // An Item receipt states the sample (its seeds and membership). Issued while the inspection is
            // still open, it would tell the Agent which Items a second sample or the resample could not
            // be, or let it redraw them: every Item receipt of the Task waits until the inspection ends.
            return false;
        }
        boolean exists = receiptRepository.findByTaskId(task.id()).stream()
                .anyMatch(r -> Objects.equals(r.itemNo(), itemNo));
        if (exists) {
            return false;
        }
        try {
            UUID receiptId = UUID.randomUUID();
            Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String keyId = signer.keyId();
            ReceiptBodyAssembler.Subject subject = new ReceiptBodyAssembler.Subject(task, builderId.orElse(null),
                    itemNo == null ? null : items.item(itemNo), items.count(), settlement, itemSettlement);
            String body = JsonCanonicalizer.canonicalize(assembler.body(receiptId, issuedAt, keyId, subject));
            String signature = signer.sign(body.getBytes(StandardCharsets.UTF_8));
            return receiptRepository.insertIfAbsent(
                    new ReceiptModel(receiptId, task.id(), itemNo, keyId, body, signature, issuedAt));
        } catch (RuntimeException e) {
            log.warn("Could not issue the receipt for task {} item {}", task.id(), itemNo, e);
            return false;
        }
    }

    /**
     * Whether the Task's sampling inspection is still deciding (a sample out, or the rework running). A
     * Task that ended (timed out, failed) closes it: nothing more will be drawn.
     */
    private boolean inspectionOpen(TaskModel task) {
        return !task.status().isClosed() && inspectionRepository.findByTaskId(task.id())
                .map(i -> !i.status().isFinished()).orElse(false);
    }

    private Optional<UUID> builderOf(TaskModel task) {
        return task.agentVersionId() == null ? Optional.empty()
                : agentRepository.findOwnerByVersionId(task.agentVersionId());
    }

    private static void requireParty(TaskModel task, Optional<UUID> builderId, Reader reader, String what, UUID id) {
        boolean party = task.clientId().equals(reader.userId())
                || (reader.asBuilder() && builderId.map(reader.userId()::equals).orElse(false));
        if (!party) {
            throw notFound(what, id);
        }
    }

    private static DomainException notFound(String what, UUID id) {
        return new DomainException(ResultCode.NOT_FOUND, "No " + what + ": " + id);
    }
}
