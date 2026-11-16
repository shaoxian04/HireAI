package com.hireai.domain.biz.task.model;

import com.hireai.domain.biz.acceptance.contract.model.Contract;
import com.hireai.utility.result.ResultCode;
import com.hireai.domain.biz.task.enums.FailureReason;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.enums.TaskResolution;
import com.hireai.domain.biz.task.enums.TaskStatus;
import com.hireai.utility.exception.DomainException;
import com.hireai.domain.shared.model.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Task aggregate root. A task is the unit of work a client submits; its budget is
 * frozen in escrow at submission. Behaviour (the submit factory + the routing/execution
 * transitions) lives here, not in setters. The aggregate is framework-free and IMMUTABLE:
 * every transition returns a NEW copy and an illegal transition throws a {@link DomainException}.
 */
public final class TaskModel {

    private final UUID id;
    private final UUID clientId;
    private final String title;
    private final String description;
    private final Money budget;
    private final OutputSpec outputSpec;
    /**
     * The Contract frozen at submit (ADR 0009), derived from the output spec. Null only on Tasks
     * submitted before Contracts existed (V39), which finish under their output spec.
     */
    private final Contract contract;
    private final String category;
    private final TaskStatus status;
    private final UUID agentVersionId; // nullable until assigned
    private final TaskResultModel result; // nullable until a result is recorded
    private final Instant createdAt;
    private final TaskResolution resolution;   // nullable until RESOLVED
    private final Instant resolvedAt;          // nullable until RESOLVED
    private final String rejectionReason;      // nullable; only set on REJECTED
    private final RejectReason rejectReasonCategory; // nullable; A/B/C/D once reviewed via reject
    private final FailureReason failureReason; // nullable; set once when the task ends without a delivered result

    // Must stay in sync with the V9 rejection_reason DB CHECK and RejectTaskRequest's @Size(max).
    private static final int MAX_REASON_LENGTH = 500;

    /** Canonical 17-arg constructor: used by the rehydration path and all transition helpers. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, Contract contract, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt,
                     TaskResolution resolution, Instant resolvedAt, String rejectionReason,
                     RejectReason rejectReasonCategory, FailureReason failureReason) {
        this.id = id;
        this.clientId = clientId;
        this.title = title;
        this.description = description;
        this.budget = budget;
        this.outputSpec = outputSpec;
        this.contract = contract;
        this.category = category;
        this.status = status;
        this.agentVersionId = agentVersionId;
        this.result = result;
        this.createdAt = createdAt;
        this.resolution = resolution;
        this.resolvedAt = resolvedAt;
        this.rejectionReason = rejectionReason;
        this.rejectReasonCategory = rejectReasonCategory;
        this.failureReason = failureReason;
    }

    /** Rehydration of a Task without a Contract (submitted before V39): delegates to the canonical constructor. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt,
                     TaskResolution resolution, Instant resolvedAt, String rejectionReason,
                     RejectReason rejectReasonCategory, FailureReason failureReason) {
        this(id, clientId, title, description, budget, outputSpec, null, category, status, agentVersionId,
                result, createdAt, resolution, resolvedAt, rejectionReason, rejectReasonCategory, failureReason);
    }

    /** Pre-review rehydration overload (11-arg, no Contract): delegates to the canonical constructor. */
    public TaskModel(UUID id, UUID clientId, String title, String description,
                     Money budget, OutputSpec outputSpec, String category, TaskStatus status,
                     UUID agentVersionId, TaskResultModel result, Instant createdAt) {
        this(id, clientId, title, description, budget, outputSpec, null, category, status,
                agentVersionId, result, createdAt, null, null, null, null, null);
    }

    /**
     * Factory for the SUBMIT transition: enforces invariants and creates a SUBMITTED task, freezing
     * the Contract its output spec maps onto (spec D39).
     */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec, String category) {
        return submit(clientId, title, description, budget, outputSpec, category, null);
    }

    /**
     * The SUBMIT transition freezing an explicit {@code contract} (#91: a direct booking under the
     * Client's terms, which the Agent's Offer covers). Null freezes the output spec's equivalent. The
     * caller checks the Contract against its Template before saving.
     */
    public static TaskModel submit(UUID clientId, String title, String description,
                                   Money budget, OutputSpec outputSpec, String category, Contract contract) {
        requirePresent(clientId, "client id");
        requireText(title, "title");
        requireText(description, "description");
        requirePositive(budget);
        requirePresent(outputSpec, "output spec");
        requireText(category, "category");
        // Lowercase to match AgentVersionModel.normaliseCategories' .trim().toLowerCase() idiom,
        // so the routing re-filter's case-sensitive containment check (coversCategory) actually matches.
        return new TaskModel(UUID.randomUUID(), clientId, title.trim(), description.trim(),
                budget, outputSpec, contract != null ? contract : outputSpec.toContract(),
                category.trim().toLowerCase(), TaskStatus.SUBMITTED,
                null, null, Instant.now(), null, null, null, null, null);
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, field + " must not be blank");
        }
    }

    private static void requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Budget must be positive");
        }
    }

    // --- Routing & execution transitions (immutable: each returns a NEW TaskModel) ---

    /** SUBMITTED/AWAITING_AGENT → QUEUED: a matching agent version was selected (re-match included). */
    public TaskModel assignAndQueue(UUID agentVersionId) {
        requireStatusIn("assignAndQueue", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, agentVersionId, this.result);
    }

    /**
     * QUEUED → QUEUED with another Agent: routed failover (#78). The platform picked the Agent, and
     * it could not take the task before starting (unreachable after the retry schedule, or busy),
     * so the platform hands the task to another version. Only a task still QUEUED qualifies: once
     * the Agent started (EXECUTING) or the task ended, it is never swapped. The new version must
     * differ from the current one; an Agent already tried is excluded before this is called.
     */
    public TaskModel reassign(UUID newAgentVersionId) {
        requireStatus(TaskStatus.QUEUED, "reassign");
        requirePresent(newAgentVersionId, "agent version id");
        if (newAgentVersionId.equals(this.agentVersionId)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Cannot reassign task " + id + " to the version it is already assigned to");
        }
        return copyWith(TaskStatus.QUEUED, newAgentVersionId, this.result);
    }

    /**
     * QUEUED → EXECUTING: the WorkOrder reached the agent. Idempotent: from any state past
     * QUEUED (the agent already called back, or the task timed out / failed meanwhile) it is a
     * no-op that returns {@code this}. Only the pre-assignment states (SUBMITTED,
     * AWAITING_AGENT) are illegal sources.
     */
    public TaskModel markExecuting() {
        if (this.status == TaskStatus.SUBMITTED || this.status == TaskStatus.AWAITING_AGENT) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Illegal transition markExecuting from " + this.status);
        }
        if (this.status != TaskStatus.QUEUED) {
            return this;
        }
        return copyWith(TaskStatus.EXECUTING, this.agentVersionId, this.result);
    }

    /**
     * QUEUED/EXECUTING → RESULT_RECEIVED: the agent posted a result back. QUEUED is allowed
     * because a fast agent may call back before the dispatch consumer marks the task EXECUTING.
     */
    public TaskModel recordResult(TaskResultModel result) {
        requireStatusIn("recordResult", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        requirePresent(result, "result");
        return copyWith(TaskStatus.RESULT_RECEIVED, this.agentVersionId, result);
    }

    /** RESULT_RECEIVED → PENDING_REVIEW: automated validation passed; awaits client review. */
    public TaskModel passValidation() {
        requireStatus(TaskStatus.RESULT_RECEIVED, "passValidation");
        return copyWith(TaskStatus.PENDING_REVIEW, this.agentVersionId, this.result);
    }

    /** RESULT_RECEIVED → SPEC_VIOLATION: automated validation failed; result is rejected by the gate. */
    public TaskModel failValidation() {
        requireStatus(TaskStatus.RESULT_RECEIVED, "failValidation");
        return endWithoutResult(TaskStatus.SPEC_VIOLATION, FailureReason.SPEC_VIOLATION);
    }

    /**
     * RESULT_RECEIVED → QUEUED for the same Agent: a deterministic check rejected the result and it
     * goes back for rework (spec D17). The rejected result stays until the reworked one replaces it.
     */
    public TaskModel returnForRework() {
        requireStatus(TaskStatus.RESULT_RECEIVED, "returnForRework");
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, this.agentVersionId, this.result);
    }

    /**
     * PENDING_REVIEW → QUEUED for the same Agent: a multi-Item Task's sample failed twice, so the whole
     * Task goes back once for rework and is resampled when it returns (spec S8, D19).
     */
    public TaskModel returnForSampleRework() {
        requireStatus(TaskStatus.PENDING_REVIEW, "returnForSampleRework");
        requirePresent(agentVersionId, "agent version id");
        return copyWith(TaskStatus.QUEUED, this.agentVersionId, this.result);
    }

    /** SUBMITTED → AWAITING_AGENT (idempotent from AWAITING_AGENT for re-match no-match passes). */
    public TaskModel markAwaitingAgent() {
        requireStatusIn("markAwaitingAgent", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        return copyWith(TaskStatus.AWAITING_AGENT, this.agentVersionId, this.result);
    }

    /**
     * SUBMITTED|AWAITING_AGENT → CANCELLED: routing attempts exhausted before any Agent was
     * assigned (the SUBMITTED sweeper or the re-match sweeper); the caller refunds escrow and
     * names why no Agent was reached.
     */
    public TaskModel markCancelled(FailureReason reason) {
        requireStatusIn("markCancelled", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT);
        return endWithoutResult(TaskStatus.CANCELLED, reason);
    }

    /**
     * SUBMITTED|AWAITING_AGENT|QUEUED → CANCELLED with {@code AGENT_UNAVAILABLE}: the pinned Agent is
     * no longer ACTIVE, or its booked version is no longer current, so a direct booking fails fast
     * (#77) instead of waiting for a version that will not come back. QUEUED qualifies because
     * availability is re-checked before every send (#58): the WorkOrder never went out.
     */
    public TaskModel cancelAsUnavailable() {
        requireStatusIn("cancelAsUnavailable", TaskStatus.SUBMITTED, TaskStatus.AWAITING_AGENT, TaskStatus.QUEUED);
        return endWithoutResult(TaskStatus.CANCELLED, FailureReason.AGENT_UNAVAILABLE);
    }

    // --- Dispatch-side predicates (the rules the dispatch path's guards apply) ---

    /** QUEUED for {@code agentVersionId}: its WorkOrder may still be sent (or re-sent on a retry). */
    public boolean isAwaitingDispatchTo(UUID agentVersionId) {
        return this.status == TaskStatus.QUEUED && agentVersionId != null
                && agentVersionId.equals(this.agentVersionId);
    }

    /** QUEUED or EXECUTING: assigned to an Agent and not yet ended or resulted. */
    public boolean isInFlight() {
        return this.status == TaskStatus.QUEUED || this.status == TaskStatus.EXECUTING;
    }

    /**
     * In flight with {@code agentVersionId}: a dispatch outcome for that version (a dead letter, a
     * busy reply) still applies. False once the task ended or failed over to another version.
     */
    public boolean isInFlightOn(UUID agentVersionId) {
        return isInFlight() && agentVersionId != null && agentVersionId.equals(this.agentVersionId);
    }

    /**
     * Whether a dispatch failure before the Agent started is routed failover (#78) rather than the
     * end of the task: only while QUEUED (the Agent never started) and only when the platform picked
     * the Agent. A direct booking ({@code pinned}) is never substituted.
     */
    public boolean failsOverOnDispatchFailure(boolean pinned) {
        return this.status == TaskStatus.QUEUED && !pinned;
    }

    /**
     * QUEUED/EXECUTING → TIMED_OUT: no result before the execution deadline. The reason follows
     * {@link #timeoutIsAgentFault()}: EXECUTION_TIMEOUT once the Agent had the work, otherwise
     * DISPATCH_ABANDONED (the WorkOrder never got through).
     */
    public TaskModel markTimedOut() {
        requireStatusIn("markTimedOut", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return endWithoutResult(TaskStatus.TIMED_OUT,
                timeoutIsAgentFault() ? FailureReason.EXECUTION_TIMEOUT : FailureReason.DISPATCH_ABANDONED);
    }

    /**
     * Whether a timeout now is the Agent's fault: only once the Agent accepted the WorkOrder
     * (EXECUTING). A task still QUEUED never reached it (lost or undelivered message), so a
     * timeout from there refunds the client without penalising the Agent (#66).
     */
    public boolean timeoutIsAgentFault() {
        return this.status == TaskStatus.EXECUTING;
    }

    /** QUEUED/EXECUTING → FAILED: dispatch/execution failed terminally, for the given reason. */
    public TaskModel markFailed(FailureReason reason) {
        requireStatusIn("markFailed", TaskStatus.QUEUED, TaskStatus.EXECUTING);
        return endWithoutResult(TaskStatus.FAILED, reason);
    }

    /**
     * PENDING_REVIEW|AWAITING_CLIENT_DECISION → RESOLVED (ACCEPTED): the client accepted the result,
     * either on review or after the Agent stood by it following a rejection (#95). The rejection's
     * reason is kept, so the record shows the result was once rejected.
     */
    public TaskModel accept() {
        requireStatusIn("accept", TaskStatus.PENDING_REVIEW, TaskStatus.AWAITING_CLIENT_DECISION);
        return resolved(TaskResolution.ACCEPTED, this.rejectionReason);
    }

    // --- Rejection response path (#95, spec D20) ---

    /**
     * PENDING_REVIEW → AWAITING_AGENT_RESPONSE: the client rejected for reason A/B/C. The Agent may
     * now revise once, concede or stand by; nothing is arbitrated yet.
     */
    public TaskModel awaitAgentResponse(RejectReason reasonCategory, String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "awaitAgentResponse");
        requireDisputable(reasonCategory);
        return withRejection(TaskStatus.AWAITING_AGENT_RESPONSE, this.result, trimReason(reason), reasonCategory);
    }

    /**
     * AWAITING_AGENT_RESPONSE → RESULT_RECEIVED: the Agent revised its result. The revision replaces
     * the delivered result and goes through validation again, like the first one.
     */
    public TaskModel revise(TaskResultModel revised) {
        requireStatus(TaskStatus.AWAITING_AGENT_RESPONSE, "revise");
        requirePresent(revised, "revised result");
        if (!revised.taskId().equals(this.id)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A revision must be for task " + id);
        }
        return withRejection(TaskStatus.RESULT_RECEIVED, revised, this.rejectionReason, this.rejectReasonCategory);
    }

    /** AWAITING_AGENT_RESPONSE → RESOLVED (REJECTED): the Agent conceded; the client is refunded. */
    public TaskModel concede() {
        requireStatus(TaskStatus.AWAITING_AGENT_RESPONSE, "concede");
        return resolved(TaskResolution.REJECTED, this.rejectionReason);
    }

    /**
     * AWAITING_AGENT_RESPONSE → AWAITING_CLIENT_DECISION: the Agent stood by its result, with a
     * reason or by staying silent. The client now accepts or requests arbitration.
     */
    public TaskModel awaitClientDecision() {
        requireStatus(TaskStatus.AWAITING_AGENT_RESPONSE, "awaitClientDecision");
        return withRejection(TaskStatus.AWAITING_CLIENT_DECISION, this.result, this.rejectionReason,
                this.rejectReasonCategory);
    }

    /**
     * AWAITING_CLIENT_DECISION → DISPUTED: the client asked for AI arbitration of the rejection the
     * Agent stood by. Only the client opens arbitration (D17′); no deposit is taken.
     */
    public TaskModel requestArbitration() {
        requireStatus(TaskStatus.AWAITING_CLIENT_DECISION, "requestArbitration");
        return withRejection(TaskStatus.DISPUTED, this.result, this.rejectionReason, this.rejectReasonCategory);
    }

    private TaskModel withRejection(TaskStatus newStatus, TaskResultModel newResult, String reason,
                                    RejectReason reasonCategory) {
        return new TaskModel(id, clientId, title, description, budget, outputSpec, contract, category,
                newStatus, agentVersionId, newResult, createdAt,
                this.resolution, this.resolvedAt, reason, reasonCategory, this.failureReason);
    }

    private static void requireDisputable(RejectReason reasonCategory) {
        requirePresent(reasonCategory, "reject reason category");
        if (!reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an A/B/C reason starts the rejection response path; got " + reasonCategory);
        }
    }

    /**
     * PENDING_REVIEW → RESOLVED once every Item of a multi-Item Task has settled (spec S3): ACCEPTED
     * when every Item was accepted, else PARTIALLY_ACCEPTED (some were refunded).
     */
    public TaskModel resolveItems(boolean allItemsAccepted) {
        requireStatus(TaskStatus.PENDING_REVIEW, "resolveItems");
        return resolved(allItemsAccepted ? TaskResolution.ACCEPTED : TaskResolution.PARTIALLY_ACCEPTED, null);
    }

    /**
     * PENDING_REVIEW → RESOLVED: a decisive AI Review (API/MCP, spec D38) settled the result. The
     * resolution follows the verdict's ruling category, as a dispute ruling's would.
     */
    public TaskModel resolveByAiReview(com.hireai.domain.biz.adjudication.enums.RulingCategory category) {
        requireStatus(TaskStatus.PENDING_REVIEW, "resolveByAiReview");
        requirePresent(category, "ruling category");
        return resolved(switch (category) {
            case FULFILLED -> TaskResolution.ACCEPTED;
            case PARTIALLY_FULFILLED -> TaskResolution.PARTIALLY_ACCEPTED;
            case NOT_FULFILLED -> TaskResolution.REJECTED;
        }, null);
    }

    /**
     * PENDING_REVIEW → RESOLVED REJECTED: every Item of a multi-Item Task settled and none was paid
     * anything (each was refunded on its own).
     */
    public TaskModel resolveItemsAllRefunded() {
        requireStatus(TaskStatus.PENDING_REVIEW, "resolveItemsAllRefunded");
        return resolved(TaskResolution.REJECTED, null);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): the client rejected the result. Reason optional, ≤500 chars. */
    public TaskModel reject(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "reject");
        return resolved(TaskResolution.REJECTED, trimReason(reason));
    }

    /** PENDING_REVIEW → DISPUTED: client rejected with a disputable reason (A/B/C); arbitration opens. */
    public TaskModel dispute(RejectReason reasonCategory, String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "dispute");
        requirePresent(reasonCategory, "reject reason category");
        if (!reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "dispute() requires an A/B/C reason; got " + reasonCategory);
        }
        return new TaskModel(id, clientId, title, description, budget, outputSpec, contract, category,
                TaskStatus.DISPUTED, agentVersionId, result, createdAt,
                this.resolution, this.resolvedAt, trimReason(reason), reasonCategory, this.failureReason);
    }

    /** DISPUTED → RESOLVED: the arbitration ruling (or fallback) has been settled. */
    public TaskModel resolveDispute(TaskResolution resolution) {
        requireStatus(TaskStatus.DISPUTED, "resolveDispute");
        requirePresent(resolution, "resolution");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, contract, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                resolution, Instant.now(), this.rejectionReason, this.rejectReasonCategory, this.failureReason);
    }

    /** PENDING_REVIEW → RESOLVED (REJECTED): client changed their mind (D); charged in full, no dispute. */
    public TaskModel chargeChangedMind(String reason) {
        requireStatus(TaskStatus.PENDING_REVIEW, "chargeChangedMind");
        return new TaskModel(id, clientId, title, description, budget, outputSpec, contract, category,
                TaskStatus.RESOLVED, agentVersionId, result, createdAt,
                TaskResolution.REJECTED, Instant.now(), trimReason(reason), RejectReason.D_CHANGED_MIND,
                this.failureReason);
    }

    private static String trimReason(String reason) {
        String trimmed = (reason == null || reason.isBlank()) ? null : reason.trim();
        if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Rejection reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    private TaskModel resolved(TaskResolution resolution, String rejectionReason) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.contract, this.category, TaskStatus.RESOLVED, this.agentVersionId, this.result,
                this.createdAt, resolution, Instant.now(), rejectionReason, this.rejectReasonCategory,
                this.failureReason);
    }

    private TaskModel copyWith(TaskStatus newStatus, UUID newAgentVersionId, TaskResultModel newResult) {
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.contract, this.category, newStatus, newAgentVersionId, newResult, this.createdAt,
                this.resolution, this.resolvedAt, this.rejectionReason, this.rejectReasonCategory,
                this.failureReason);
    }

    /** Terminal transition without a delivered result: records the FailureReason, exactly once. */
    private TaskModel endWithoutResult(TaskStatus terminalStatus, FailureReason reason) {
        requirePresent(reason, "failure reason");
        if (this.failureReason != null) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Failure reason already set to " + this.failureReason + "; cannot record " + reason);
        }
        return new TaskModel(this.id, this.clientId, this.title, this.description, this.budget,
                this.outputSpec, this.contract, this.category, terminalStatus, this.agentVersionId, this.result,
                this.createdAt, this.resolution, this.resolvedAt, this.rejectionReason,
                this.rejectReasonCategory, reason);
    }

    private void requireStatus(TaskStatus expected, String transition) {
        if (this.status != expected) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Illegal transition " + transition + " from " + this.status + "; expected " + expected);
        }
    }

    private void requireStatusIn(String transition, TaskStatus... allowed) {
        for (TaskStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                "Illegal transition " + transition + " from " + this.status);
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public String title() { return title; }
    public String description() { return description; }
    public Money budget() { return budget; }
    public OutputSpec outputSpec() { return outputSpec; }
    /** The frozen Contract; null on a Task submitted before Contracts existed. */
    public Contract contract() { return contract; }
    public String category() { return category; }
    public TaskStatus status() { return status; }
    public UUID agentVersionId() { return agentVersionId; }
    public TaskResultModel result() { return result; }
    public Instant createdAt() { return createdAt; }
    public TaskResolution resolution() { return resolution; }
    public Instant resolvedAt() { return resolvedAt; }
    public String rejectionReason() { return rejectionReason; }
    public RejectReason rejectReasonCategory() { return rejectReasonCategory; }
    public FailureReason failureReason() { return failureReason; }
}
