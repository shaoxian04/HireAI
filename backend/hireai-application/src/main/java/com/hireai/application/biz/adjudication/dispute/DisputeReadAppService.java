package com.hireai.application.biz.adjudication.dispute;

import com.hireai.domain.biz.adjudication.model.DisputeModel;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public interface DisputeReadAppService {

    /**
     * The dispute (with full ruling history) for a task, visible only to the task's client or the
     * builder who owns the agent version that ran it. Anyone else, or no dispute, → NOT_FOUND.
     */
    DisputeModel getOutcomeForUser(@NonNull UUID taskId, @NonNull UUID currentUserId);
}
