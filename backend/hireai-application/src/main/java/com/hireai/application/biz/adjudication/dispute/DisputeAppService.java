package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.TaskModel;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

public interface DisputeAppService {

    /** Open a dispute for an already-DISPUTED task and request a ruling; settles inline if the ruling is synchronous. */
    UUID openDispute(@NonNull TaskModel disputedTask, @NonNull UUID raisedBy, @NonNull RejectReason reasonCategory);

    /** Apply an arbitrator ruling (first-ruling-wins): settle by category + resolve. No-op if not resolvable. */
    void applyRuling(@NonNull UUID disputeId, @NonNull RulingInfo ruling);

    /** Platform refund fallback (DLQ 兜底): full refund + resolve. */
    void resolveByFallback(@NonNull UUID disputeId);
}
