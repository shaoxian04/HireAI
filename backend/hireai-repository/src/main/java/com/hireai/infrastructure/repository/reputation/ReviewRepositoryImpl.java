package com.hireai.infrastructure.repository.reputation;

import com.hireai.domain.biz.reputation.model.ReviewModel;
import com.hireai.domain.biz.reputation.repository.ReviewRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link ReviewRepository}. Maps
 * {@code ReviewModel} &lt;-&gt; JPA entity. The save() method upserts by PK and
 * preserves {@code gmt_create} on update (only the first write sets the creation timestamp).
 */
@Repository
public class ReviewRepositoryImpl implements ReviewRepository {

    private final ReviewJpaRepository jpa;

    public ReviewRepositoryImpl(ReviewJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ReviewModel save(ReviewModel review) {
        Instant now = Instant.now();
        // Read-before-write upsert: non-atomic, acceptable here — a review has a single writer per flow.
        Instant created = jpa.findById(review.id())
                .map(ReviewDO::getGmtCreate)
                .orElse(now);
        jpa.save(new ReviewDO(
                review.id(), review.taskId(), review.clientId(), review.agentId(),
                (short) review.rating(), review.reviewText(), review.builderResponse(),
                review.published(), created, now));
        return review;
    }

    @Override
    public Optional<ReviewModel> findById(UUID reviewId) {
        return jpa.findById(reviewId).map(this::toModel);
    }

    @Override
    public List<ReviewModel> findPublishedByAgentId(UUID agentId, int limit) {
        return jpa.findByAgentIdAndIsPublishedTrueOrderByGmtCreateDesc(agentId, PageRequest.of(0, limit))
                .stream()
                .map(this::toModel)
                .toList();
    }

    private ReviewModel toModel(ReviewDO entity) {
        return new ReviewModel(
                entity.getId(), entity.getTaskId(), entity.getClientId(), entity.getAgentId(),
                (int) entity.getRating(), entity.getReviewText(), entity.getBuilderResponse(),
                entity.isPublished(), entity.getGmtCreate());
    }
}
