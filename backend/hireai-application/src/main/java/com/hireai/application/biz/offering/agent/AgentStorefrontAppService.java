package com.hireai.application.biz.offering.agent;

import com.hireai.application.port.query.BuilderStatsQueryPort;
import com.hireai.domain.biz.offering.storefront.info.ProfileUpdateInfo;
import com.hireai.domain.biz.offering.storefront.model.StorefrontModel;
import com.hireai.domain.biz.reputation.model.ReviewModel;
import org.jspecify.annotations.NonNull;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Builder-side storefront management. EVERY method re-derives ownership through
 * AgentReadAppService.getForOwner (Invariant #5) before touching profile, media, or reviews.
 */
@Validated
public interface AgentStorefrontAppService {

    StorefrontModel getProfile(@NonNull UUID agentId, @NonNull UUID ownerId);

    StorefrontModel updateProfile(@NonNull UUID agentId, @NonNull UUID ownerId,
                                    @NonNull ProfileUpdateInfo info);

    StorefrontModel uploadMedia(@NonNull UUID agentId, @NonNull UUID ownerId,
                                  @NonNull String kind, @NonNull String contentType,
                                  long sizeBytes, byte @NonNull [] bytes);

    StorefrontModel removeMedia(@NonNull UUID agentId, @NonNull UUID ownerId,
                                  @NonNull String kind, @NonNull String url);

    List<ReviewModel> reviews(@NonNull UUID agentId, @NonNull UUID ownerId);

    ReviewModel respondToReview(@NonNull UUID agentId, @NonNull UUID ownerId,
                                @NonNull UUID reviewId, @NonNull String response);

    BuilderStatsQueryPort.StatsBundle getStats(@NonNull UUID agentId, @NonNull UUID ownerId);
}
