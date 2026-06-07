package com.hireai.infrastructure.repository.agent;

import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of the domain {@link AgentProfileRepository}. Maps
 * {@code AgentProfileModel} &lt;-&gt; JPA entity. The save() method upserts by PK and
 * preserves {@code gmt_create} on update (only the first write sets the creation timestamp).
 */
@Repository
public class AgentProfileRepositoryImpl implements AgentProfileRepository {

    private final AgentProfileJpaRepository jpa;

    public AgentProfileRepositoryImpl(AgentProfileJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public AgentProfileModel save(AgentProfileModel profile) {
        Instant now = Instant.now();
        // Read-before-write upsert: non-atomic, acceptable here — a profile has a single writer (its owner).
        Instant created = jpa.findById(profile.agentId())
                .map(AgentProfileJpaEntity::getGmtCreate)
                .orElse(now);
        jpa.save(new AgentProfileJpaEntity(
                profile.agentId(), profile.tagline(), profile.description(), profile.sampleOutput(),
                profile.logoUrl(), profile.coverUrl(), profile.galleryUrls(),
                profile.listed(), profile.featured(), created, now));
        return profile;
    }

    @Override
    public Optional<AgentProfileModel> findByAgentId(UUID agentId) {
        return jpa.findById(agentId).map(this::toModel);
    }

    private AgentProfileModel toModel(AgentProfileJpaEntity entity) {
        List<String> gallery = entity.getGalleryUrls() != null
                ? List.copyOf(entity.getGalleryUrls())
                : List.of();
        return new AgentProfileModel(
                entity.getAgentId(), entity.getTagline(), entity.getDescription(),
                entity.getSampleOutput(), entity.getLogoUrl(), entity.getCoverUrl(),
                gallery, entity.isListed(), entity.isFeatured());
    }
}
