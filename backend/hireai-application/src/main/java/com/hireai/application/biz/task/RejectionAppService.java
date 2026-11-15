package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The rejection response path (#95, spec D20) after a Client rejects a result for reason A/B/C
 * ({@link TaskReviewAppService#reject} opens the round):
 * <ol>
 *   <li>The Agent is sent a signed notice and, within its response window, may revise once
 *       (validated and reviewed again), concede (full refund, quality 0) or stand by with a reason.
 *       Silence counts as standing by without a reason.</li>
 *   <li>After a stand-by the Client, within their decision window, accepts
 *       ({@link TaskReviewAppService#accept}) or requests AI arbitration. Silence counts as
 *       acceptance. Only the Client opens arbitration, and no deposit is taken.</li>
 * </ol>
 * Every change takes the task's row lock first, so a response, a click and a sweeper pass on the
 * same task serialize and only one of them wins.
 */
@Validated
public interface RejectionAppService {

    /**
     * Records the Agent's response. The caller authenticated it first (dispatch token for this task
     * plus a fresh signature with the Agent's current secret); the token's version must still be the
     * task's assigned one. Refused with DOMAIN_RULE_VIOLATION when the task is not awaiting a
     * response, the window has closed, or a revision is no longer allowed.
     */
    void respond(@NonNull UUID taskId, @NonNull String bearerToken, @NonNull AgentRejectionResponseInfo response);

    /** The Client asks for AI arbitration of a result the Agent stood by. Ownership-checked. */
    UUID requestArbitration(@NonNull UUID taskId, @NonNull UUID clientId);

    /** The task's latest rejection round, for a task the caller already loaded for its owner. */
    Optional<RejectionModel> findLatest(@NonNull UUID taskId);

    /** Tasks whose Agent response window closed without a response (the sweeper's read). */
    List<UUID> responseExpiredTaskIds();

    /** Counts the Agent's silence as standing by. Row-locked, idempotent, a no-op while the window is open. */
    void standBySilentlyOne(@NonNull UUID taskId);

    /** Tasks whose Client decision window closed without a decision (the sweeper's read). */
    List<UUID> decisionExpiredTaskIds();

    /** Counts the Client's silence as acceptance and settles. Row-locked, idempotent. */
    void acceptSilentlyOne(@NonNull UUID taskId);

    /** Tasks whose rejection notice has not reached the Agent yet and may still be retried. */
    List<UUID> noticePendingTaskIds();

    /**
     * Sends the latest round's notice to the Agent's webhook, once: a no-op when it was already
     * delivered or the Agent has responded. A failed send is counted and retried by the sweeper.
     */
    void deliverNotice(@NonNull UUID taskId);
}
