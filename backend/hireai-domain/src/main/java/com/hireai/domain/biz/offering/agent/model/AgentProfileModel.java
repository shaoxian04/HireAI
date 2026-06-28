package com.hireai.domain.biz.offering.agent.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storefront content for an Agent (1:1 with the aggregate root, table agent_profiles).
 * Marketing concern only — never touches the routable contract. Immutable: every change
 * returns a new copy. Catalogue visibility = agents.status ACTIVE AND listed here.
 */
public final class AgentProfileModel {

    public static final int MAX_GALLERY = 6;
    public static final int MAX_TAGLINE = 160;
    public static final int MAX_TEXT = 8000;

    private final UUID agentId;
    private final String tagline;
    private final String description;
    private final String sampleOutput;
    private final String logoUrl;
    private final String coverUrl;
    private final List<String> galleryUrls;
    private final boolean listed;
    private final boolean featured;

    public AgentProfileModel(UUID agentId, String tagline, String description, String sampleOutput,
                             String logoUrl, String coverUrl, List<String> galleryUrls,
                             boolean listed, boolean featured) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.logoUrl = logoUrl;
        this.coverUrl = coverUrl;
        this.galleryUrls = List.copyOf(galleryUrls);
        this.listed = listed;
        this.featured = featured;
    }

    /** Factory for registration: empty, unlisted, unfeatured. */
    public static AgentProfileModel createDefault(UUID agentId) {
        if (agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "agent id is required");
        }
        return new AgentProfileModel(agentId, null, null, null, null, null, List.of(), false, false);
    }

    /** Builder edits the storefront text + listing toggle. */
    public AgentProfileModel updateContent(String tagline, String description,
                                           String sampleOutput, boolean listed) {
        return new AgentProfileModel(agentId,
                limited(tagline, MAX_TAGLINE, "tagline"),
                limited(description, MAX_TEXT, "description"),
                limited(sampleOutput, MAX_TEXT, "sample output"),
                logoUrl, coverUrl, galleryUrls, listed, featured);
    }

    public AgentProfileModel withLogo(String url) {
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                url, coverUrl, galleryUrls, listed, featured);
    }

    public AgentProfileModel withCover(String url) {
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                logoUrl, url, galleryUrls, listed, featured);
    }

    /**
     * Gallery capacity rule — the single home for the max. Lets callers (e.g. the media-upload
     * flow) fail fast before an expensive upload without duplicating the threshold.
     */
    public void assertCanAddGallery() {
        if (galleryUrls.size() >= MAX_GALLERY) {
            throw new DomainException(ResultCode.DOMAIN_RULE_VIOLATION,
                    "Gallery is full (max " + MAX_GALLERY + " images)");
        }
    }

    public AgentProfileModel addGalleryUrl(String url) {
        assertCanAddGallery();
        List<String> next = new ArrayList<>(galleryUrls);
        next.add(url);
        return new AgentProfileModel(agentId, tagline, description, sampleOutput,
                logoUrl, coverUrl, next, listed, featured);
    }

    /** Removes a media entry; kind is logo|cover|gallery. Unknown URL is a no-op for gallery. */
    public AgentProfileModel removeMedia(String kind, String url) {
        return switch (kind) {
            case "logo" -> withLogo(null);
            case "cover" -> withCover(null);
            case "gallery" -> {
                List<String> next = new ArrayList<>(galleryUrls);
                next.remove(url);
                yield new AgentProfileModel(agentId, tagline, description, sampleOutput,
                        logoUrl, coverUrl, next, listed, featured);
            }
            default -> throw new DomainException(ResultCode.VALIDATION_ERROR,
                    "Unknown media kind: " + kind);
        };
    }

    private static String limited(String value, int max, String field) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new DomainException(ResultCode.VALIDATION_ERROR,
                    field + " must be at most " + max + " characters");
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    public UUID agentId() { return agentId; }
    public String tagline() { return tagline; }
    public String description() { return description; }
    public String sampleOutput() { return sampleOutput; }
    public String logoUrl() { return logoUrl; }
    public String coverUrl() { return coverUrl; }
    public List<String> galleryUrls() { return galleryUrls; }
    public boolean listed() { return listed; }
    public boolean featured() { return featured; }
}
