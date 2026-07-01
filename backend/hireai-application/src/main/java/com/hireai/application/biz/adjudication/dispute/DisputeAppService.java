package com.hireai.application.biz.adjudication.dispute;

import com.hireai.application.biz.adjudication.port.RulingInfo;
import com.hireai.domain.biz.adjudication.enums.RulingCategory;
import com.hireai.domain.biz.task.enums.RejectReason;
import com.hireai.domain.biz.task.model.TaskModel;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface DisputeAppService {

    /** Open a dispute for an already-DISPUTED task and request a ruling; settles inline if the ruling is synchronous. */
    UUID openDispute(@NonNull TaskModel disputedTask, @NonNull UUID raisedBy, @NonNull RejectReason reasonCategory);

    /** Apply an arbitrator ruling (first-ruling-wins): settle by category + resolve. No-op if not resolvable. */
    void applyRuling(@NonNull UUID disputeId, @NonNull RulingInfo ruling);

    /** OPEN|ARBITRATING → ESCALATED (DLQ or stale-sweep). No-op if already resolved/escalated. */
    void escalate(@NonNull UUID disputeId);

    /** Human-backstop ruling on an un-settled dispute: settle once by category, resolve (tier-2 ADMINISTRATOR). */
    void adminRule(@NonNull UUID disputeId, @NonNull RulingCategory category,
                   String rationale, @NonNull UUID adminId);

    /** Ids of disputes stuck in ARBITRATING since before {@code cutoff} (for the sweeper). */
    List<UUID> staleArbitratingDisputeIds(@NonNull Instant cutoff);
}
