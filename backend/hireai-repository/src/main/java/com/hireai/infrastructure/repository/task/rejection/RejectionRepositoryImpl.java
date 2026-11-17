package com.hireai.infrastructure.repository.task.rejection;

import com.hireai.domain.biz.task.rejection.model.RejectionModel;
import com.hireai.domain.biz.task.rejection.repository.RejectionRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RejectionRepositoryImpl implements RejectionRepository {

    private final RejectionJpaRepository jpa;

    public RejectionRepositoryImpl(RejectionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void save(RejectionModel r) {
        jpa.save(new RejectionDO(r.id(), r.taskId(), r.round(), r.reasonCategory(), r.reason(), r.rejectedAt(),
                r.responseDeadline(), r.revisionAllowed(), r.response(), r.responseReason(), r.responseSilent(),
                r.respondedAt(), r.decisionDeadline(), r.decision(), r.decisionSilent(), r.decidedAt()));
    }

    @Override
    public Optional<RejectionModel> findLatestByTaskId(UUID taskId) {
        return jpa.findFirstByTaskIdOrderByRoundDesc(taskId).map(RejectionRepositoryImpl::toModel);
    }

    @Override
    public List<RejectionModel> findByTaskId(UUID taskId) {
        return jpa.findByTaskIdOrderByRoundAsc(taskId).stream().map(RejectionRepositoryImpl::toModel).toList();
    }

    @Override
    public List<UUID> findTaskIdsResponseExpired(Instant now) {
        return jpa.findTaskIdsResponseExpired(now);
    }

    @Override
    public List<UUID> findTaskIdsDecisionExpired(Instant now) {
        return jpa.findTaskIdsDecisionExpired(now);
    }

    @Override
    public List<UUID> findTaskIdsNoticePending(Instant now, int maxAttempts) {
        return jpa.findTaskIdsNoticePending(now, maxAttempts);
    }

    @Override
    public void recordNoticeAttempt(UUID rejectionId, Instant deliveredAt) {
        jpa.recordNoticeAttempt(rejectionId, deliveredAt);
    }

    @Override
    public void recordReplacedResult(UUID rejectionId, String resultPayload, String resultUrl) {
        jpa.recordReplacedResult(rejectionId, resultPayload, resultUrl);
    }

    private static RejectionModel toModel(RejectionDO d) {
        return new RejectionModel(d.getId(), d.getTaskId(), d.getRound(), d.getReasonCategory(), d.getReason(),
                d.getRejectedAt(), d.getResponseDeadline(), d.isRevisionAllowed(), d.getResponse(),
                d.getResponseReason(), d.isResponseSilent(), d.getRespondedAt(), d.getDecisionDeadline(),
                d.getDecision(), d.isDecisionSilent(), d.getDecidedAt());
    }
}
