package com.hireai.application.biz.task.sampling;

import com.hireai.application.biz.acceptance.source.SourceSnapshotAppService;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.info.AgentRejectionResponseInfo;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.rejection.enums.ClientDecision;
import com.hireai.domain.biz.task.sampling.model.InspectionModel;
import com.hireai.domain.biz.task.sampling.model.ItemJudgementModel;
import com.hireai.domain.biz.task.sampling.model.ItemRejectionModel;
import com.hireai.domain.biz.task.sampling.model.SampleDrawModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sampling inspection of a web-channel multi-Item Task (spec S8: D4, D6, D17′, D19, D21).
 * <ol>
 *   <li>When the Items are delivered, the lot (the Items that passed the Contract's checks) is sampled
 *       under the shared lot-size table at the Task's inspection level, from a seed drawn then and
 *       recorded, so the Agent cannot know which Items will be checked.</li>
 *   <li>The buyer's reviewer judges Items: a pass accepts and pays the Item; a fail opens the Item's
 *       rejection response path (the Agent revises once, concedes, or stands by; then the buyer
 *       decides). The reviewer may also judge Items outside the sample; those are handled on their own
 *       and never feed the error rate.</li>
 *   <li>Once every sampled Item has settled, the sample is judged: pass → every Item still awaiting
 *       review is accepted; inconclusive → the second sample; fail → the whole Task is reworked once and
 *       resampled; fail again → the Items still awaiting review settle pro rata.</li>
 * </ol>
 * Every change takes the Task's row lock first. Money moves only through the settlement services'
 * deterministic ledger entries (Invariants #2, #3).
 */
public interface SamplingAppService {

    /**
     * A multi-Item Task's Items were judged and it awaits review (caller holds the row lock; web channel):
     * draws the round's first sample, or resolves the Task when nothing is left to review.
     */
    void onItemsDelivered(TaskModel pendingReview);

    /**
     * The buyer's reviewer judges one Item awaiting review (ownership-checked; the review window must be
     * open). A fail needs an A/B/C reason and says what is wrong.
     */
    void judge(UUID taskId, UUID clientId, int itemNo, boolean passed, RejectReason reasonCategory, String reason);

    /** The buyer accepted everything awaiting review, or let the window close (caller holds the row lock). */
    void waive(UUID taskId);

    /**
     * The Agent's response to a reviewer's rejection of one Item (authenticated by the caller: dispatch
     * token for this Task plus a fresh signature with the Agent's current secret).
     */
    void respondToItemRejection(UUID taskId, int itemNo, String bearerToken, AgentRejectionResponseInfo response);

    /** The buyer's decision on an Item the Agent stood by (ownership-checked). */
    void decideItem(UUID taskId, UUID clientId, int itemNo, ClientDecision decision);

    List<UUID> itemResponseExpiredIds();

    /** The Agent's response window for this Item closed: silence counts as standing by. */
    void standBySilentlyItem(UUID itemId);

    List<UUID> itemDecisionExpiredIds();

    /** The buyer's decision window for this Item closed: silence counts as acceptance. */
    void acceptSilentlyItem(UUID itemId);

    List<UUID> itemNoticePendingIds();

    /** Sends (or retries) the Agent the Item's rejection notice. */
    void deliverItemNotice(UUID itemId);

    /** The Task's inspection for its buyer (ownership-checked); empty when it is not sampled. */
    Optional<InspectionView> inspectionFor(UUID taskId, UUID clientId);

    /** An Item's source document for the buyer's reviewer to compare against (ownership-checked). */
    SourceSnapshotAppService.Download sourceFor(UUID taskId, UUID clientId, int itemNo);

    record InspectionView(InspectionModel inspection, List<SampleDrawModel> draws,
                          List<ItemJudgementModel> judgements, List<ItemRejectionModel> rejections) {
    }
}
