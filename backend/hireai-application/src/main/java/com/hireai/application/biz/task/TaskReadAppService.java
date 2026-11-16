package com.hireai.application.biz.task;

import com.hireai.domain.biz.task.dispatchattempt.info.DispatchAttemptView;
import com.hireai.domain.biz.task.info.ReviewWindowState;
import com.hireai.domain.biz.task.info.TaskRoutingView;
import com.hireai.domain.biz.task.model.TaskModel;
import com.hireai.domain.biz.task.model.TaskResultModel;
import com.hireai.domain.biz.task.repository.TaskQuery;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;
import com.hireai.domain.biz.acceptance.template.info.VersionFallback;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import com.hireai.domain.biz.task.item.info.DispatchItem;
import com.hireai.domain.biz.task.item.model.TaskItems;

/**
 * Orchestrates task READ use cases. Enforces Hard Invariant #5 (server-side identity +
 * ownership): a task is only returned to the client that owns it; otherwise NOT_FOUND,
 * so existence is not leaked across clients. {@link #getResult} additionally returns NOT_FOUND
 * when an owned task has no result yet (it is not PENDING_REVIEW), which the UI reads as
 * "pending, keep polling". {@link #getRoutingView} is an internal, non-owner-scoped read used by
 * the routing module (no client identity is involved in routing).
 */
@Validated
public interface TaskReadAppService {

    TaskModel getForClient(@NonNull UUID taskId, @NonNull UUID clientId);

    /**
     * The Agent a task was booked with: the version it was assigned to, else the version the
     * Client pinned (a direct booking that never got assigned). Empty for a routed task that never
     * found an Agent. Takes a task the caller has already loaded for its owner. The web rebook
     * excludes this Agent from the new shortlist (#79).
     */
    Optional<UUID> findBookedAgentId(@NonNull TaskModel task);

    /**
     * The review window of a task the caller has already loaded for its owner (#94): when it
     * closes, and whether it closed unreviewed and was accepted on the Client's behalf. Empty when
     * the task never awaited review on the web channel.
     */
    Optional<ReviewWindowState> findReviewWindow(@NonNull TaskModel task);

    /** Why the result waits for the Client's review (ANOMALY, UNDECIDED, AI_REVIEW_UNAVAILABLE); empty otherwise. */
    Optional<String> findReviewReason(@NonNull TaskModel task);

    /**
     * Why no Agent matched a task the caller has already loaded for its owner (#91): shown while it
     * waits AWAITING_AGENT and once it ended NO_ELIGIBLE_AGENT, empty otherwise.
     */
    Optional<String> findFailureDetail(@NonNull TaskModel task);

    /**
     * Whether the task's frozen Contract uses the previous Template version during its transition
     * (spec D36), so the Client is told; empty otherwise (and for tasks without a Contract).
     */
    Optional<VersionFallback> findTemplateFallback(@NonNull TaskModel task);

    /** What requirement extraction made of the task's description (mode 1, spec S6); empty when none ran. */
    Optional<com.hireai.domain.biz.acceptance.extraction.model.ExtractionRecord> findContractExtraction(
            @NonNull TaskModel task);

    TaskResultModel getResult(@NonNull UUID taskId, @NonNull UUID clientId);

    /** The result's DeliverySnapshot (D24), or empty when it has none (not a file, or not fetched). */
    Optional<DeliverySnapshot> findDeliverySnapshot(@NonNull TaskResultModel result);

    /**
     * The frozen file of the client's own task: its snapshot and bytes, never re-fetched from the
     * Agent's link. NOT_FOUND for another client's task, a task with no snapshot, or retired content.
     */
    FrozenDeliverable getDeliverable(@NonNull UUID taskId, @NonNull UUID clientId);

    /** A DeliverySnapshot with its bytes, for the owner-only download. */
    record FrozenDeliverable(DeliverySnapshot snapshot, byte[] content) {
    }

    List<TaskModel> listForClient(@NonNull UUID clientId, @NonNull TaskQuery query);

    TaskRoutingView getRoutingView(@NonNull UUID taskId);

    /**
     * The task's dispatch history in attempt order (#75), to its owner only: NOT_FOUND for any
     * other client. Empty while the task has not been dispatched.
     */
    List<DispatchAttemptView> listDispatchAttempts(@NonNull UUID taskId, @NonNull UUID clientId);

    /** A multi-Item Task's Items as its WorkOrder carries them (spec S3); empty for a single-deliverable Task. */
    List<DispatchItem> workOrderItems(UUID taskId);

    /** The Task's Items in Item order; empty for a single-deliverable Task (no ownership check: pass an owned Task). */
    TaskItems findItems(TaskModel task);

    /** The Items of several Tasks (list views); Tasks without Items are absent from the map. */
    Map<UUID, TaskItems> findItems(Collection<UUID> taskIds);
}
