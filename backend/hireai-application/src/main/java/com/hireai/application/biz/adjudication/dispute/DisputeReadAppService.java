package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.dispute.view.DisputeMineRow;
import com.hireai.application.biz.adjudication.dispute.view.DisputeOutcomeView;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface DisputeReadAppService {

    /**
     * The dispute (with full ruling history, fees and who may still act) for a task, visible only
     * to the task's client or the builder who owns the agent version that ran it. Anyone else, or no
     * dispute, → NOT_FOUND.
     */
    DisputeOutcomeView getOutcomeForUser(@NonNull UUID taskId, @NonNull UUID currentUserId);

    /**
     * The same view, looked up by the dispute's own id — for the refreshed view after accept/appeal.
     * Same visibility rule as {@link #getOutcomeForUser}.
     */
    DisputeOutcomeView getOutcomeByDispute(@NonNull UUID disputeId, @NonNull UUID currentUserId);

    /** All disputes the given client raised (action-needed first), for the /client/disputes surface. */
    List<DisputeMineRow> myDisputes(@NonNull UUID clientId);

    /** All disputes on tasks run by the builder's Agents (action-needed first), for /builder/disputes. */
    List<DisputeMineRow> builderDisputes(@NonNull UUID builderId);
}
