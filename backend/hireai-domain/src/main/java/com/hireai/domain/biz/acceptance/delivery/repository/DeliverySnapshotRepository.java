package com.hireai.domain.biz.acceptance.delivery.repository;

import com.hireai.domain.biz.acceptance.delivery.model.DeliverySnapshot;

import java.util.Optional;
import java.util.UUID;

/** Persistence of DeliverySnapshot metadata (append-only). The bytes are stored by content hash elsewhere. */
public interface DeliverySnapshotRepository {

    DeliverySnapshot save(DeliverySnapshot snapshot);

    /** The snapshot taken for one result (a revised result has its own id). */
    Optional<DeliverySnapshot> findByTaskResultId(UUID taskResultId);
}
