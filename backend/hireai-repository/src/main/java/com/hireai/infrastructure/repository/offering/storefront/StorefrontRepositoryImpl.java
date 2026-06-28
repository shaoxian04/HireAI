package com.hireai.infrastructure.repository.offering.storefront;

import com.hireai.domain.biz.offering.storefront.model.Media;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.offering.storefront.repository.StorefrontRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure implementation of {@link StorefrontRepository}. Maps {@code StorefrontModel} &lt;-&gt;
 * {@link StorefrontDO}, splitting/joining the {@link Media} VO over the logo/cover/gallery columns.
 * save() upserts by PK and preserves {@code gmt_create} on update (single writer = the owner).
 */
@Repository
public class StorefrontRepositoryImpl implements StorefrontRepository {

    private final StorefrontJpaRepository jpa;

    public StorefrontRepositoryImpl(StorefrontJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public StorefrontModel save(StorefrontModel storefront) {
        Instant now = Instant.now();
        Instant created = jpa.findById(storefront.agentId())
                .map(StorefrontDO::getGmtCreate)
                .orElse(now);
        Media media = storefront.media();
        jpa.save(new StorefrontDO(
                storefront.agentId(), storefront.tagline(), storefront.description(),
                storefront.sampleOutput(), media.logoUrl(), media.coverUrl(), media.galleryUrls(),
                storefront.listed(), storefront.featured(), created, now));
        return storefront;
    }

    @Override
    public Optional<StorefrontModel> findByAgentId(UUID agentId) {
        return jpa.findById(agentId).map(this::toModel);
    }

    private StorefrontModel toModel(StorefrontDO entity) {
        List<String> gallery = entity.getGalleryUrls() != null
                ? List.copyOf(entity.getGalleryUrls())
                : List.of();
        return new StorefrontModel(
                entity.getAgentId(), entity.getTagline(), entity.getDescription(),
                entity.getSampleOutput(),
                new Media(entity.getLogoUrl(), entity.getCoverUrl(), gallery),
                entity.isListed(), entity.isFeatured());
    }
}
