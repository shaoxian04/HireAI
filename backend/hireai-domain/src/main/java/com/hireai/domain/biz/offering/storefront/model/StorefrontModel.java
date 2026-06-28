package com.hireai.domain.biz.offering.storefront.model;

import com.hireai.utility.result.ResultCode;
import com.hireai.utility.exception.DomainException;

import java.util.List;
import java.util.UUID;

/**
 * Storefront aggregate root: an Agent's public marketing presence (1:1 with the Agent aggregate,
 * table agent_profiles). Holds the text content + listing flags and a {@link Media} value object
 * (logo/cover/gallery). Marketing concern only — never touches the routable contract. Immutable:
 * every change returns a new copy. Catalogue visibility = agents.status ACTIVE AND listed here.
 */
public final class StorefrontModel {

    public static final int MAX_TAGLINE = 160;
    public static final int MAX_TEXT = 8000;
    /** Re-exported so callers that reach the cap via the root need not import Media. */
    public static final int MAX_GALLERY = Media.MAX_GALLERY;

    private final UUID agentId;
    private final String tagline;
    private final String description;
    private final String sampleOutput;
    private final Media media;
    private final boolean listed;
    private final boolean featured;

    public StorefrontModel(UUID agentId, String tagline, String description, String sampleOutput,
                           Media media, boolean listed, boolean featured) {
        this.agentId = agentId;
        this.tagline = tagline;
        this.description = description;
        this.sampleOutput = sampleOutput;
        this.media = media == null ? Media.empty() : media;
        this.listed = listed;
        this.featured = featured;
    }

    /** Factory for registration: empty, unlisted, unfeatured. */
    public static StorefrontModel createDefault(UUID agentId) {
        if (agentId == null) {
            throw new DomainException(ResultCode.VALIDATION_ERROR, "agent id is required");
        }
        return new StorefrontModel(agentId, null, null, null, Media.empty(), false, false);
    }

    /** Builder edits the storefront text + listing toggle. */
    public StorefrontModel updateContent(String tagline, String description,
                                         String sampleOutput, boolean listed) {
        return new StorefrontModel(agentId,
                limited(tagline, MAX_TAGLINE, "tagline"),
                limited(description, MAX_TEXT, "description"),
                limited(sampleOutput, MAX_TEXT, "sample output"),
                media, listed, featured);
    }

    public StorefrontModel withLogo(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.withLogo(url), listed, featured);
    }

    public StorefrontModel withCover(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.withCover(url), listed, featured);
    }

    /** Gallery capacity rule — delegates to the Media VO (the single home for the max). */
    public void assertCanAddGallery() {
        media.assertCanAddGallery();
    }

    public StorefrontModel addGalleryUrl(String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.addGalleryUrl(url), listed, featured);
    }

    /** Removes a media entry; kind is logo|cover|gallery. */
    public StorefrontModel removeMedia(String kind, String url) {
        return new StorefrontModel(agentId, tagline, description, sampleOutput,
                media.remove(kind, url), listed, featured);
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
    public Media media() { return media; }
    public boolean listed() { return listed; }
    public boolean featured() { return featured; }

    // Convenience read accessors (delegate to Media) so adapters/DTOs need not reach through media().
    public String logoUrl() { return media.logoUrl(); }
    public String coverUrl() { return media.coverUrl(); }
    public List<String> galleryUrls() { return media.galleryUrls(); }
}
