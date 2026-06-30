package com.hireai.domain.biz.adjudication.repository;

import com.hireai.domain.biz.adjudication.model.DisputeModel;

import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository {
    DisputeModel save(DisputeModel dispute);
    Optional<DisputeModel> findById(UUID id);
    Optional<DisputeModel> findByTaskId(UUID taskId);
}
