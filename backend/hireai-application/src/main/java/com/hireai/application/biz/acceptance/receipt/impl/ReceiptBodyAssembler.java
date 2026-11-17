package com.hireai.application.biz.acceptance.receipt.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hireai.application.biz.acceptance.AcceptanceJsonMapper;
import com.hireai.application.biz.task.TaskReadAppService;
import com.hireai.application.port.query.ReceiptEvidenceQueryPort;
import com.hireai.application.port.query.ReceiptEvidenceQueryPort.LedgerLine;
import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.delivery.repository.DeliverySnapshotRepository;
import com.hireai.domain.biz.acceptance.receipt.model.ReceiptModel;
import com.hireai.domain.biz.adjudication.model.CheckResult;
import com.hireai.domain.biz.adjudication.model.DisputeModel;
import com.hireai.domain.biz.adjudication.model.Ruling;
import com.hireai.domain.biz.adjudication.model.ValidationReportModel;
import com.hireai.domain.biz.adjudication.repository.DisputeRepository;
import com.hireai.domain.biz.adjudication.repository.ValidationReportRepository;
import com.hireai.domain.biz.ledger.settlement.info.DisputeFeeCharge;
import com.hireai.domain.biz.ledger.settlement.model.ItemSettlementModel;
import com.hireai.domain.biz.ledger.settlement.model.TaskSettlementRecord;
import com.hireai.domain.biz.ledger.settlement.model.SettlementModel;
import com.hireai.domain.biz.ledger.settlement.repository.DisputeFeeRepository;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.item.enums.ItemStatus;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;
import com.hireai.domain.biz.task.sampling.model.SampleSizeTable;
import com.hireai.domain.biz.task.sampling.repository.InspectionRepository;
import com.hireai.domain.biz.ledger.settlement.repository.ItemSettlementRepository;
import com.hireai.domain.biz.task.sampling.repository.ItemRejectionRepository;
import com.hireai.domain.biz.task.item.model.ItemCheck;
import com.hireai.domain.biz.task.item.model.TaskItemModel;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.repository.RejectionRepository;
import com.hireai.domain.shared.model.Money;
import com.hireai.utility.canonical.JsonCanonicalizer;
import com.hireai.utility.hash.Sha256;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Gathers what a receipt states about one settled subject and lays it out in format
 * {@value ReceiptModel#FORMAT} (docs/details/receipts.md). Absent facts are left out, never written as
 * null, so later fields (the sample of #105, an Item's {@code source} hash of #104, {@code findings}, the
 * {@code aiReview}) are added as new optional members without changing the receipts already signed.
 */
@Component
@RequiredArgsConstructor
class ReceiptBodyAssembler {

    private final AcceptanceJsonMapper acceptanceJsonMapper;
    private final ObjectMapper objectMapper;
    private final TaskReadAppService taskReadAppService;
    private final ValidationReportRepository validationReportRepository;
    private final DeliverySnapshotRepository deliverySnapshotRepository;
    private final RejectionRepository rejectionRepository;
    private final DisputeRepository disputeRepository;
    private final DisputeFeeRepository disputeFeeRepository;
    private final ReceiptEvidenceQueryPort evidence;
    private final ReceiptPartyRefs partyRefs;
    /** A sampled multi-Item Task's draws and reviewer judgements (spec S8, D6/D21: membership and seed). */
    private final InspectionRepository inspectionRepository;
    private final ItemRejectionRepository itemRejectionRepository;
    private final ItemSettlementRepository itemSettlementRepository;
    /** An Item's source document (spec S11: its hash, never its bytes). */
    private final com.hireai.domain.biz.acceptance.source.repository.SourceSnapshotRepository sourceSnapshotRepository;
    /** A Task's AI Review and its verdict (S10). */
    private final com.hireai.domain.biz.acceptance.aireview.repository.AiReviewRepository aiReviewRepository;

    /** The facts about the subject that the caller has already loaded. */
    record Subject(TaskModel task, UUID builderId, TaskItemModel item, int itemCount,
                   SettlementModel settlement, ItemSettlementModel itemSettlement) {
    }

    Map<String, Object> body(UUID receiptId, Instant issuedAt, String keyId, Subject s) {
        TaskModel task = s.task();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", ReceiptModel.FORMAT);
        body.put("receiptId", receiptId.toString());
        body.put("issuedAt", time(issuedAt));
        body.put("keyId", keyId);
        body.put("task", map("id", task.id().toString(), "submittedAt", time(task.createdAt())));
        if (s.item() != null) {
            body.put("item", map("no", s.item().itemNo(), "count", s.itemCount()));
        } else if (s.itemCount() > 0) {
            body.put("itemCount", s.itemCount());
        }
        // Pseudonymous, per task: a receipt leaves HireAI, so it never carries an internal user id.
        body.put("parties", map(
                "clientRef", partyRefs.ref(task.id(), ReceiptPartyRefs.CLIENT, task.clientId()),
                "builderRef", partyRefs.ref(task.id(), ReceiptPartyRefs.BUILDER, s.builderId()),
                "agentVersionRef", partyRefs.ref(task.id(), ReceiptPartyRefs.AGENT_VERSION, task.agentVersionId())));
        body.put("contract", contract(task));
        body.put("input", map("sha256", Sha256.hex(s.item() != null ? s.item().input() : task.description())));
        if (s.item() != null && s.item().sourceSnapshotId() != null) {
            sourceSnapshotRepository.findById(s.item().sourceSnapshotId()).ifPresent(src -> body.put("source",
                    map("sha256", src.sha256(), "mediaType", src.mediaType(), "sizeBytes", src.sizeBytes())));
        }

        if (s.item() != null) {
            putIfPresent(body, "delivery", itemDelivery(s.item()));
            putIfPresent(body, "checks", itemChecks(s.item()));
            putIfPresent(body, "findings", findings(s.item().findings()));
            putIfPresent(body, "sample", sample(s.item()));
            body.put("judgements", itemJudgements(s.item()));
            body.put("settlement", itemSettlement(task, s.itemSettlement()));
        } else {
            putIfPresent(body, "delivery", delivery(task.result()));
            putIfPresent(body, "checks", validationReportRepository.findLatestByTaskId(task.id())
                    .map(ReceiptBodyAssembler::checks).orElse(null));
            putIfPresent(body, "findings", validationReportRepository.findLatestByTaskId(task.id())
                    .map(r -> findings(r.findings())).orElse(null));
            aiReviewRepository.findByTaskId(task.id()).ifPresent(r -> putIfPresent(body, "aiReview", aiReview(r)));
            body.put("judgements", judgements(task));
            Optional<DisputeModel> dispute = disputeRepository.findByTaskId(task.id());
            dispute.ifPresent(d -> putIfPresent(body, "ruling", ruling(d)));
            dispute.ifPresent(d -> putIfPresent(body, "fees", fees(task, d)));
            body.put("settlement", settlement(task, s.settlement()));
        }
        return body;
    }

    /**
     * Facts about the input (D45); they never changed the pay. Left out when there are none. Only each
     * Finding's {@code code}: a receipt leaves HireAI, and a Finding's detail can name other tasks, a supplier's
     * tax id or an invoice number, so that text stays in the Client's own task views.
     */
    private static List<Object> findings(List<com.hireai.domain.biz.acceptance.check.model.Finding> findings) {
        if (findings == null || findings.isEmpty()) {
            return null;
        }
        List<Object> list = new ArrayList<>();
        findings.forEach(f -> list.add(map("code", f.code())));
        return list;
    }

    /** The AI Review booked on the Task (S10): its fee, outcome and, once it ran, the verdict per criterion. */
    private static Map<String, Object> aiReview(com.hireai.domain.biz.acceptance.aireview.model.AiReviewModel r) {
        List<Object> verdicts = new ArrayList<>();
        r.verdicts().forEach(v -> verdicts.add(map("criterion", v.criterion(), "passed", v.passed())));
        Map<String, Object> out = map("status", r.status().name(), "decisive", r.decisive(), "fee", money(r.fee()));
        if (!verdicts.isEmpty()) {
            out.put("verdicts", verdicts);
            out.put("rulingCategory", r.rulingCategory().name());
        }
        return out;
    }

    /** The Contract's Template and the SHA-256 of its RFC 8785 form (the Task's frozen Contract). */
    private Map<String, Object> contract(TaskModel task) {
        Contract contract = task.contract();
        Object tree;
        try {
            tree = objectMapper.readValue(acceptanceJsonMapper.toJson(contract), Object.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Contract JSON did not parse", e);
        }
        return map("template", contract.template().toString(),
                "templateKey", contract.template().key(),
                "templateVersion", contract.template().version(),
                "sha256", Sha256.hex(JsonCanonicalizer.canonicalize(tree)));
    }

    private Map<String, Object> delivery(TaskResultModel result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> delivery = map("agentStatus", result.agentStatus(),
                "receivedAt", time(result.receivedAt()),
                "payloadSha256", result.resultPayloadJson() == null ? null : Sha256.hex(result.resultPayloadJson()));
        deliverySnapshotRepository.findByTaskResultId(result.id())
                .ifPresent(snapshot -> delivery.put("file", file(snapshot)));
        return delivery;
    }

    private Map<String, Object> itemDelivery(TaskItemModel item) {
        if (item.agentStatus() == null) {
            return null;
        }
        Map<String, Object> delivery = map("agentStatus", item.agentStatus(),
                "payloadSha256", item.resultPayload() == null ? null : Sha256.hex(item.resultPayload()));
        // The Item's latest attempt (a rework or revision is frozen beside the first, #97).
        deliverySnapshotRepository.findLatestForItem(item.id())
                .ifPresent(snapshot -> delivery.put("file", file(snapshot)));
        return delivery;
    }

    private static Map<String, Object> file(DeliverySnapshot snapshot) {
        return map("sha256", snapshot.sha256(), "mediaType", snapshot.mediaType(),
                "sizeBytes", snapshot.sizeBytes(), "fetchedAt", time(snapshot.fetchedAt()));
    }

    private static Map<String, Object> checks(ValidationReportModel report) {
        List<Object> results = new ArrayList<>();
        for (CheckResult c : report.checks()) {
            results.add(map("rule", c.rule(), "passed", c.passed(), "detail", c.detail()));
        }
        return map("attempt", report.attemptNo(), "verdict", report.verdict().name(), "results", results);
    }

    private static Map<String, Object> itemChecks(TaskItemModel item) {
        if (item.checks().isEmpty()) {
            return null;
        }
        List<Object> results = new ArrayList<>();
        for (ItemCheck c : item.checks()) {
            // A check that sent the Item to review (D7) says so; PASS and REJECT read from "passed" as before.
            results.add(map("rule", c.rule(), "passed", c.passed(), "detail", c.detail(),
                    "outcome", c.sendsToReview() ? c.outcome().name() : null));
        }
        String verdict = item.checks().stream().anyMatch(ItemCheck::rejects) ? "FAIL"
                : item.needsReview() ? "REVIEW" : "PASS";
        return map("verdict", verdict, "results", results);
    }

    /** The rejection rounds (#95) and the Client's final decision on the result, in order. */
    private List<Object> judgements(TaskModel task) {
        List<Object> judgements = new ArrayList<>();
        for (RejectionModel r : rejectionRepository.findByTaskId(task.id())) {
            judgements.add(map("by", "CLIENT", "decision", "REJECTED", "round", r.round(),
                    "reasonCategory", r.reasonCategory() == null ? null : r.reasonCategory().name(),
                    "at", time(r.rejectedAt())));
            if (r.response() != null) {
                judgements.add(map("by", "AGENT", "response", r.response().name(),
                        "silent", r.responseSilent() ? Boolean.TRUE : null, "at", time(r.respondedAt())));
            }
            if (r.decision() != null) {
                judgements.add(map("by", "CLIENT", "decision", r.decision().name(),
                        "silent", r.decisionSilent() ? Boolean.TRUE : null, "at", time(r.decidedAt())));
            }
        }
        if (task.resolution() == TaskResolution.ACCEPTED && !disputeRepository.findByTaskId(task.id()).isPresent()) {
            boolean automatic = taskReadAppService.findReviewWindow(task).map(ReviewWindowState::autoAccepted)
                    .orElse(false);
            judgements.add(map("by", "CLIENT", "decision", "ACCEPTED",
                    "automatic", automatic ? Boolean.TRUE : null, "at", time(task.resolvedAt())));
        } else if (task.resolution() == TaskResolution.REJECTED && task.rejectReasonCategory() != null
                && judgements.isEmpty()) {
            judgements.add(map("by", "CLIENT", "decision", "REJECTED",
                    "reasonCategory", task.rejectReasonCategory().name(), "at", time(task.resolvedAt())));
        }
        return judgements;
    }

    /**
     * Whether the Item was in a sample of the inspection, under which plan and from which seed (D6,
     * D21); absent for an Item of a Task that was not sampled. Anyone with the seed redraws the sample.
     */
    private Map<String, Object> sample(TaskItemModel item) {
        Optional<InspectionModel> inspection = inspectionRepository.findByTaskId(item.taskId());
        if (inspection.isEmpty()) {
            return null;
        }
        List<Object> draws = new ArrayList<>();
        Boolean sampled = Boolean.FALSE;
        for (SampleDrawModel d : inspectionRepository.findDraws(item.taskId())) {
            boolean member = d.contains(item.itemNo());
            sampled = sampled || member;
            draws.add(map("round", d.round(), "stage", d.stage(), "seed", Long.toString(d.seed()),
                    "lotSize", d.plan().lotSize(), "codeLetter", String.valueOf(d.plan().codeLetter()),
                    "size", d.itemNos().size(), "member", member,
                    "defects", d.defects(), "decision", d.decision() == null ? null : d.decision().name()));
        }
        InspectionModel i = inspection.get();
        return map("table", "ISO2859-1", "aql", SampleSizeTable.AQL.toPlainString(),
                "inspectionLevel", i.level().name(), "outcome", i.status().name(),
                "errorRate", i.errorRate() == null ? null : i.errorRate().toPlainString(),
                "sampled", sampled, "draws", draws);
    }

    private List<Object> itemJudgements(TaskItemModel item) {
        List<Object> judgements = new ArrayList<>();
        // The buyer's reviewer (D4) and each rejection round of this Item (D17'), oldest first.
        for (ItemJudgementModel j : inspectionRepository.findJudgements(item.taskId())) {
            if (j.itemId().equals(item.id())) {
                judgements.add(map("by", "REVIEWER", "decision", j.passed() ? "PASSED" : "REJECTED",
                        "round", j.round(), "sampled", j.sampled(),
                        "reasonCategory", j.reasonCategory() == null ? null : j.reasonCategory().name(),
                        "at", time(j.judgedAt())));
            }
        }
        for (ItemRejectionModel r : itemRejectionRepository.findByTaskId(item.taskId())) {
            if (r.itemId().equals(item.id()) && r.round().response() != null) {
                judgements.add(map("by", "AGENT", "response", r.round().response().name(),
                        "silent", r.round().responseSilent() ? Boolean.TRUE : null, "at", time(r.round().respondedAt())));
            }
            if (r.itemId().equals(item.id()) && r.round().decision() != null) {
                judgements.add(map("by", "CLIENT", "decision", r.round().decision().name(),
                        "silent", r.round().decisionSilent() ? Boolean.TRUE : null, "at", time(r.round().decidedAt())));
            }
        }
        if (item.status() == ItemStatus.ACCEPTED) {
            judgements.add(map("by", "CLIENT", "decision", "ACCEPTED",
                    "automatic", item.autoAccepted() ? Boolean.TRUE : null));
        }
        return judgements;
    }

    private static Map<String, Object> ruling(DisputeModel dispute) {
        Optional<Ruling> effective = dispute.effectiveRuling();
        if (effective.isEmpty()) {
            return null;
        }
        List<Object> history = new ArrayList<>();
        for (Ruling r : dispute.rulings()) {
            history.add(map("tier", r.tier(), "category", r.category().name(), "decidedBy", r.decidedBy().name(),
                    "decidedAt", time(r.decidedAt()), "rationale", r.rationale()));
        }
        return map("disputeId", dispute.id().toString(),
                "category", effective.get().category().name(),
                "appealedBy", dispute.appealedBy() == null ? null : dispute.appealedBy().name(),
                "rulings", history);
    }

    private Map<String, Object> fees(TaskModel task, DisputeModel dispute) {
        List<DisputeFeeCharge> charges = disputeFeeRepository.findByDisputeId(dispute.id());
        if (charges.isEmpty()) {
            return null;
        }
        List<Object> list = new ArrayList<>();
        for (DisputeFeeCharge c : charges) {
            list.add(map("type", c.type().name(), "clientCharged", money(c.clientCharged()),
                    "builderCharged", money(c.builderCharged()), "platformCovered", money(c.platformCovered())));
        }
        // Both fee types of one dispute share one correlation id, so their entries are listed together.
        return map("charges", list,
                "ledgerEntries", ledger(task, evidence.ledgerEntries(task.id(), "dispute-fee-" + dispute.id())));
    }

    private Map<String, Object> settlement(TaskModel task, SettlementModel settlement) {
        // A multi-Item Task some of whose Items settled on their own (sampling, spec S8) refunds only what
        // was still held: that is the whole-Task settlement's price.
        Money price = new TaskSettlementRecord(task.budget(), java.util.Optional.of(settlement),
                itemSettlementRepository.findByTaskId(task.id())).stillHeld();
        BigDecimal refunded = price.value().subtract(settlement.net().value()).subtract(settlement.commission().value());
        return map("outcome", settlement.type().name(),
                "price", money(price), "paid", money(settlement.net()),
                "commission", money(settlement.commission()), "refunded", amount(refunded),
                "failureReason", task.failureReason() == null ? null : task.failureReason().name(),
                "settledAt", time(settlement.createdAt()),
                "ledgerEntries", ledger(task, evidence.ledgerEntries(task.id(), "settle-" + task.id())));
    }

    private Map<String, Object> itemSettlement(TaskModel task, ItemSettlementModel s) {
        String correlation = "settle-" + task.id() + "-item-" + s.itemNo();
        Money price = Money.of(s.net().value().add(s.commission().value()).add(s.refund().value()));
        return map("outcome", s.type().name(),
                "price", money(price), "paid", money(s.net()),
                "commission", money(s.commission()), "refunded", money(s.refund()),
                "settledAt", time(s.createdAt()),
                "ledgerEntries", ledger(task, evidence.ledgerEntries(task.id(), correlation)));
    }

    private static List<Object> ledger(TaskModel task, List<LedgerLine> lines) {
        List<Object> entries = new ArrayList<>();
        for (LedgerLine line : lines) {
            entries.add(map("id", line.id().toString(),
                    "party", task.clientId().equals(line.walletOwnerId()) ? "CLIENT" : "BUILDER",
                    "type", line.type(), "amount", amount(line.amount()), "at", time(line.createdAt())));
        }
        return entries;
    }

    private static String money(Money m) {
        return amount(m.value());
    }

    private static String amount(BigDecimal v) {
        return v.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    static String time(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    /** An ordered map of key/value pairs, leaving out every null value. */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                m.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return m;
    }
}
