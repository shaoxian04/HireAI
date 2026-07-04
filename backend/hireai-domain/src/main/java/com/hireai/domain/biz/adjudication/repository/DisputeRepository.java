package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.DisputeModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository {
    DisputeModel save(DisputeModel dispute);
    Optional<DisputeModel> findById(UUID id);
    Optional<DisputeModel> findByTaskId(UUID taskId);

    /** Ids of disputes stuck in ARBITRATING since before {@code cutoff} (for the stale-arbitration sweeper). */
    List<UUID> findStaleArbitratingIds(Instant cutoff);

    /** Ids of disputes stuck in RULED (proposed, unacted) since before {@code cutoff} (auto-accept sweeper). */
    List<UUID> findStaleRuledIds(Instant cutoff);
}
