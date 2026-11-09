package com.hireai.domain.biz.task.enums;

/**
 * Why a task ended without a delivered result (#74). The status says HOW it ended; this says WHY.
 * Set once, by the terminal transition, and shown to the client (task API, {@code task.failed}
 * webhook, MCP, web failure panel). Closed set: must stay in sync with the V32 CHECK constraint.
 */
public enum FailureReason {
    /** Every send attempt in the retry schedule failed (dispatch DLQ). */
    AGENT_UNREACHABLE,
    /** The Agent declined the WorkOrder as busy (429/503). */
    AGENT_BUSY,
    /** The client-picked Agent or version could no longer take work. */
    AGENT_UNAVAILABLE,
    /** Routed failover ran out of untried eligible Agents. */
    NO_ALTERNATIVE_AGENT,
    /** A routed task found no eligible Agent within its re-match attempts. */
    NO_ELIGIBLE_AGENT,
    /** The platform never got the WorkOrder to an Agent (routing kept failing, or the task sat QUEUED). */
    DISPATCH_ABANDONED,
    /** The Agent accepted the WorkOrder but did not deliver before the execution deadline. */
    EXECUTION_TIMEOUT,
    /** The Agent's callback reported a non-COMPLETED status. */
    AGENT_REPORTED_FAILURE,
    /** The delivered result broke the task's frozen output spec. */
    SPEC_VIOLATION;

    /**
     * Reason for cancelling a task the re-match sweeper could not place: a direct booking's pinned
     * version stayed unavailable, while a routed task simply found no eligible Agent.
     */
    public static FailureReason noAgentFound(boolean pinnedToAgent) {
        return pinnedToAgent ? AGENT_UNAVAILABLE : NO_ELIGIBLE_AGENT;
    }
}
