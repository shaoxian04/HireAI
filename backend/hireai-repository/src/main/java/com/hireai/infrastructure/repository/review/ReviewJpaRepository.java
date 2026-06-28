package com.hireai.infrastructure.repository.review;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewJpaRepository extends JpaRepository<ReviewDO, UUID> {

    List<ReviewDO> findByAgentIdAndIsPublishedTrueOrderByGmtCreateDesc(UUID agentId, Pageable page);
}
