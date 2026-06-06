package com.hireai.application.biz.agent.impl;

import com.hireai.application.biz.agent.AgentReadAppService;
import com.hireai.application.biz.agent.AgentStorefrontAppService;
import com.hireai.application.port.storage.MediaStoragePort;
import com.hireai.controller.base.ResultCode;
import com.hireai.domain.biz.agent.info.ProfileUpdateInfo;
import com.hireai.domain.biz.agent.model.AgentProfileModel;
import com.hireai.domain.biz.agent.repository.AgentProfileRepository;
import com.hireai.domain.biz.review.model.ReviewModel;
import com.hireai.domain.biz.review.repository.ReviewRepository;
import com.hireai.domain.shared.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class AgentStorefrontAppServiceImpl implements AgentStorefrontAppService {

    private static final long MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");
    private static final List<String> KINDS = List.of("logo", "cover", "gallery");
    private static final int REVIEWS_LIMIT = 50;

    private final AgentReadAppService agentReadAppService;
    private final AgentProfileRepository profileRepository;
    private final MediaStoragePort mediaStoragePort;
    private final ReviewRepository reviewRepository;

    @Override
    @Transactional(readOnly = true)
    public AgentProfileModel getProfile(UUID agentId, UUID ownerId) {
        agentReadAppService.getForOwner(agentId, ownerId); // throws NOT_FOUND when not owner
        return loadProfile(agentId);
    }

    @Override
    public AgentProfileModel updateProfile(UUID agentId, UUID ownerId, ProfileUpdateInfo info) {
        agentReadAppService.getForOwner(agentId, ownerId);
        AgentProfileModel updated = loadProfile(agentId)
                .updateContent(info.tagline(), info.description(), info.sampleOutput(), info.listed());
        return profileRepository.save(updated);
    }

    @Override
    public AgentProfileModel uploadMedia(UUID agentId, UUID ownerId, String kind,
                                         String contentType, long sizeBytes, byte[] bytes) {
        agentReadAppService.getForOwner(agentId, ownerId);
        if (!KINDS.contains(kind)) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Unknown media kind: " + kind);
        }
        String ext = ALLOWED_TYPES.get(contentType);
        if (ext == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unsupported image type (allowed: png, jpeg, webp)");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_IMAGE_BYTES) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "Image must be 1B-2MB");
        }
        AgentProfileModel profile = loadProfile(agentId);
        if ("gallery".equals(kind) && profile.galleryUrls().size() >= AgentProfileModel.MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + AgentProfileModel.MAX_GALLERY + " images)");
        }
        String objectKey = "agents/" + agentId + "/" + kind + "-" + UUID.randomUUID() + "." + ext;
        // Tech debt: remote upload inside @Transactional holds a DB connection; acceptable at demo scale.
        String url = mediaStoragePort.upload(objectKey, contentType, bytes);
        AgentProfileModel updated = switch (kind) {
            case "logo" -> profile.withLogo(url);
            case "cover" -> profile.withCover(url);
            default -> profile.addGalleryUrl(url);
        };
        return profileRepository.save(updated);
    }

    @Override
    public AgentProfileModel removeMedia(UUID agentId, UUID ownerId, String kind, String url) {
        agentReadAppService.getForOwner(agentId, ownerId);
        AgentProfileModel updated = loadProfile(agentId).removeMedia(kind, url);
        // Best-effort delete before save: if save then fails, the URL dangles but a retry re-converges; storage drift is acceptable at demo scale.
        mediaStoragePort.deleteByUrl(url);
        return profileRepository.save(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewModel> reviews(UUID agentId, UUID ownerId) {
        agentReadAppService.getForOwner(agentId, ownerId);
        return reviewRepository.findPublishedByAgentId(agentId, REVIEWS_LIMIT);
    }

    @Override
    public ReviewModel respondToReview(UUID agentId, UUID ownerId, UUID reviewId, String response) {
        agentReadAppService.getForOwner(agentId, ownerId);
        ReviewModel review = reviewRepository.findById(reviewId)
                .filter(r -> r.agentId().equals(agentId))
                .orElseThrow(() -> new DomainException(ResultCode.NOT_FOUND,
                        "Review not found: " + reviewId));
        return reviewRepository.save(review.respond(response));
    }

    private AgentProfileModel loadProfile(UUID agentId) {
        return profileRepository.findByAgentId(agentId)
                .orElseGet(() -> AgentProfileModel.createDefault(agentId)); // pre-V6 agents
    }
}
