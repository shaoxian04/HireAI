package com.hireai.domain.biz.task.rejection.model;

import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.rejection.enums.AgentResponse;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.utility.exception.DomainException;
import com.hireai.utility.result.ResultCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One round of the rejection response path (#95, spec D20): a Client's A/B/C rejection of a result,
 * the Agent's response to it within its window, and — when the Agent stood by — the Client's
 * decision within theirs. A task has one round per A/B/C rejection; a second round happens only
 * after a revision, and then may not be revised again ({@link #revisionAllowed()}).
 *
 * <p>Immutable: every transition returns a new copy and refuses (DOMAIN_RULE_VIOLATION, so 409)
 * when the round is not in the state it needs or the window it needs has closed. Silence is a
 * transition of its own ({@link #standBySilently}, {@link #acceptSilently}), allowed only once the
 * window closed, so a late click and the sweeper can never both win.
 */
public final class RejectionModel {

    /** Same bound as a rejection reason (V9 CHECK, RejectTaskRequest). */
    public static final int MAX_REASON_LENGTH = 500;

    private final UUID id;
    private final UUID taskId;
    private final int round;
    private final RejectReason reasonCategory;
    private final String reason;
    private final Instant rejectedAt;
    private final Instant responseDeadline;
    private final boolean revisionAllowed;
    private final AgentResponse response;      // null until the Agent responds or its window closes
    private final String responseReason;       // the stand-by reason; null when silent or not standing by
    private final boolean responseSilent;
    private final Instant respondedAt;
    private final Instant decisionDeadline;    // set when the Agent stands by
    private final ClientDecision decision;     // null until the Client decides or their window closes
    private final boolean decisionSilent;
    private final Instant decidedAt;

    public RejectionModel(UUID id, UUID taskId, int round, RejectReason reasonCategory, String reason,
                          Instant rejectedAt, Instant responseDeadline, boolean revisionAllowed,
                          AgentResponse response, String responseReason, boolean responseSilent,
                          Instant respondedAt, Instant decisionDeadline, ClientDecision decision,
                          boolean decisionSilent, Instant decidedAt) {
        this.id = id;
        this.taskId = taskId;
        this.round = round;
        this.reasonCategory = reasonCategory;
        this.reason = reason;
        this.rejectedAt = rejectedAt;
        this.responseDeadline = responseDeadline;
        this.revisionAllowed = revisionAllowed;
        this.response = response;
        this.responseReason = responseReason;
        this.responseSilent = responseSilent;
        this.respondedAt = respondedAt;
        this.decisionDeadline = decisionDeadline;
        this.decision = decision;
        this.decisionSilent = decisionSilent;
        this.decidedAt = decidedAt;
    }

    /** Opens round {@code round} of a task's rejection at {@code now}; the Agent's window starts. */
    public static RejectionModel open(UUID taskId, int round, RejectReason reasonCategory, String reason,
                                      boolean revisionAllowed, Instant now, RejectionWindows windows) {
        if (taskId == null || reasonCategory == null || now == null || windows == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A rejection needs a task, a reason and a time");
        }
        if (!reasonCategory.opensDispute()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Only an A/B/C rejection waits for the Agent's response; got " + reasonCategory);
        }
        if (round < 1) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A rejection round starts at 1; got " + round);
        }
        return new RejectionModel(UUID.randomUUID(), taskId, round, reasonCategory, trim(reason), now,
                windows.responseDeadline(now), revisionAllowed, null, null, false, null, null, null, false, null);
    }

    /**
     * The Agent's response, within its window. A stand-by opens the Client's decision window at
     * {@code now}; a revision is refused when this round does not allow one.
     */
    public RejectionModel respond(AgentResponse action, String standByReason, Instant now, RejectionWindows windows) {
        if (action == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A response action is required");
        }
        requireAwaitingResponse();
        if (!now.isBefore(responseDeadline)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "The response window for task " + taskId
                    + " closed at " + responseDeadline + "; silence already counts as standing by");
        }
        if (action == AgentResponse.REVISE && !revisionAllowed) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " was already revised once; concede or stand by instead");
        }
        String kept = action == AgentResponse.STAND_BY ? trim(standByReason) : null;
        Instant decisionBy = action == AgentResponse.STAND_BY ? windows.decisionDeadline(now) : null;
        return new RejectionModel(id, taskId, round, reasonCategory, reason, rejectedAt, responseDeadline,
                revisionAllowed, action, kept, false, now, decisionBy, null, false, null);
    }

    /** The Agent's window closed without a response: it counts as standing by without a reason. */
    public RejectionModel standBySilently(Instant now, RejectionWindows windows) {
        requireAwaitingResponse();
        if (!responseWindowClosed(now)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "The response window for task " + taskId + " is still open until " + responseDeadline);
        }
        return new RejectionModel(id, taskId, round, reasonCategory, reason, rejectedAt, responseDeadline,
                revisionAllowed, AgentResponse.STAND_BY, null, true, now, windows.decisionDeadline(now),
                null, false, null);
    }

    /** The Client's decision after a stand-by, within their window. */
    public RejectionModel decide(ClientDecision choice, Instant now) {
        if (choice == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "A decision is required");
        }
        requireAwaitingDecision();
        if (decisionWindowClosed(now)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION, "The decision window for task " + taskId
                    + " closed at " + decisionDeadline + "; the result is accepted automatically");
        }
        return decided(choice, false, now);
    }

    /** The Client's window closed without a decision: it counts as acceptance. */
    public RejectionModel acceptSilently(Instant now) {
        requireAwaitingDecision();
        if (!decisionWindowClosed(now)) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "The decision window for task " + taskId + " is still open until " + decisionDeadline);
        }
        return decided(ClientDecision.ACCEPT, true, now);
    }

    public boolean isAwaitingResponse() {
        return response == null;
    }

    public boolean isAwaitingDecision() {
        return response == AgentResponse.STAND_BY && decision == null;
    }

    public boolean responseWindowClosed(Instant now) {
        return !now.isBefore(responseDeadline);
    }

    public boolean decisionWindowClosed(Instant now) {
        return decisionDeadline != null && !now.isBefore(decisionDeadline);
    }

    private RejectionModel decided(ClientDecision choice, boolean silent, Instant now) {
        return new RejectionModel(id, taskId, round, reasonCategory, reason, rejectedAt, responseDeadline,
                revisionAllowed, response, responseReason, responseSilent, respondedAt, decisionDeadline,
                choice, silent, now);
    }

    private void requireAwaitingResponse() {
        if (!isAwaitingResponse()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "The Agent already responded to this rejection of task " + taskId + " (" + response + ")");
        }
    }

    private void requireAwaitingDecision() {
        if (!isAwaitingDecision()) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Task " + taskId + " is not waiting for the Client's decision on a stood-by result");
        }
    }

    private static String trim(String text) {
        String trimmed = (text == null || text.isBlank()) ? null : text.trim();
        if (trimmed != null && trimmed.length() > MAX_REASON_LENGTH) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "A reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    public UUID id() { return id; }
    public UUID taskId() { return taskId; }
    public int round() { return round; }
    public RejectReason reasonCategory() { return reasonCategory; }
    public String reason() { return reason; }
    public Instant rejectedAt() { return rejectedAt; }
    public Instant responseDeadline() { return responseDeadline; }
    public boolean revisionAllowed() { return revisionAllowed; }
    public AgentResponse response() { return response; }
    public String responseReason() { return responseReason; }
    public boolean responseSilent() { return responseSilent; }
    public Instant respondedAt() { return respondedAt; }
    public Instant decisionDeadline() { return decisionDeadline; }
    public ClientDecision decision() { return decision; }
    public boolean decisionSilent() { return decisionSilent; }
    public Instant decidedAt() { return decidedAt; }
}
